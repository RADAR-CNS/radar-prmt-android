/*
 * Copyright (C) 2010 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.radarcns.util;

import android.support.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.radarcns.util.Serialization.bytesToInt;
import static org.radarcns.util.Serialization.intToBytes;
import static org.radarcns.util.Serialization.longToBytes;

/**
 * This class is an adaptation of com.squareup.tape2, allowing multi-element writes. It also
 * removes legacy support.
 *
 * A reliable, efficient, file-based, FIFO queue. Additions and removals are O(1). Writes are
 * synchronous; data will be written to disk before an operation returns.
 * The underlying file is structured to survive process and even system crashes. If an I/O
 * exception is thrown during a mutating change, the change is aborted. It is safe to continue to
 * use a {@code QueueFile} instance after an exception.
 *
 * <p><strong>Note that this implementation is not synchronized.</strong>
 *
 * <p>In a traditional queue, the remove operation returns an element. In this queue,
 * {@link #peek} and {@link #remove} are used in conjunction. Use
 * {@code peek} to retrieve the first element, and then {@code remove} to remove it after
 * successful processing. If the system crashes after {@code peek} and during processing, the
 * element will remain in the queue, to be processed when the system restarts.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public final class QueueFile implements Closeable, Iterable<InputStream> {
    /** Initial file size in bytes. */
    public static final int MINIMUM_SIZE = 4096; // one file system block

    /** The header length in bytes. */
    public static final int HEADER_LENGTH = 36;

    private static final Logger logger = LoggerFactory.getLogger(QueueFile.class);

    /** Leading bit set to 1 indicating a versioned header and the version of 1. */
    private static final int VERSIONED_HEADER = 0x00000001;

    /**
     * The underlying file. Uses a ring buffer to store entries. Designed so that a modification
     * isn't committed or visible until we write the header. The header is much smaller than a
     * segment. Storing the file length ensures we can recover from a failed expansion
     * (i.e. if setting the file length succeeds but the process dies before the data can be
     * copied).
     * <pre>
     * Format:
     *   36 bytes      Header
     *   ...              Data
     *
     * Header (32 bytes):
     *   4 bytes          Version
     *   8 bytes          File length
     *   4 bytes          Element count
     *   8 bytes          Head element position
     *   8 bytes          Tail element position
     *   4 bytes          Header checksum
     *
     * Element:
     *   4 bytes          Data length `n`
     *   4 bytes          Element header checksum
     *   `n` bytes          Data
     * </pre>
     */
    private final FileChannel channel;
    private final RandomAccessFile randomAccessFile;
    private final int maxSize;

    /** Filename, for toString purposes */
    private final String fileName;

    /** Cached file length. Always a power of 2. */
    private int fileLength;

    /** Number of elements. */
    private int elementCount;

    /** Pointer to first (or eldest) element. */
    private Element first;

    /** Pointer to last (or newest) element. */
    private final Element last;

    private MappedByteBuffer byteBuffer;
    private final MappedByteBuffer headerByteBuffer;

    /**
     * The number of times this file has been structurally modified — it is incremented during
     * {@link #remove(int)} and {@link #elementOutputStream()}. Used by {@link ElementIterator}
     * to guard against concurrent modification.
     */
    private int modCount = 0;

    private boolean closed;
    private final byte[] elementHeaderBuffer = new byte[Element.HEADER_LENGTH];
    private final byte[] queueHeaderBuffer = new byte[QueueFile.HEADER_LENGTH];

    public QueueFile(File file) throws IOException {
        this(file, Integer.MAX_VALUE);
    }

    public QueueFile(File file, int maxSize) throws IOException {
        if (maxSize < MINIMUM_SIZE) {
            throw new IllegalArgumentException("Maximum file size must be at least QueueFile.MINIMUM_SIZE = " + MINIMUM_SIZE);
        }
        this.fileName = file.getName();
        this.maxSize = maxSize;
        this.last = new Element();

        closed = false;

        boolean exists = file.exists();
        randomAccessFile = new RandomAccessFile(file, "rw");
        channel = this.randomAccessFile.getChannel();

        if (exists) {
            fileLength = (int)(Math.min(randomAccessFile.length(), maxSize));
            if (fileLength < QueueFile.HEADER_LENGTH) {
                throw new IOException("File " + file + " does not contain QueueFile header");
            }
            updateBufferExtent();
            headerByteBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, QueueFile.HEADER_LENGTH);
            int version = headerByteBuffer.getInt();
            if (version != VERSIONED_HEADER) {
                throw new IOException("File " + file + " is not recognized as a queue file.");
            }
            long headerFileLength = headerByteBuffer.getLong();
            if (headerFileLength > maxSize) {
                throw new IOException("Queue file is larger than maximum size " + maxSize);
            }
            if (headerFileLength > fileLength) {
                throw new IOException(
                        "File is truncated. Expected length: " + headerFileLength
                                + ", Actual length: " + fileLength);
            }
            fileLength = (int)headerFileLength;

            elementCount = headerByteBuffer.getInt();
            long firstOffset = headerByteBuffer.getLong();
            long lastOffset = headerByteBuffer.getLong();

            if (firstOffset > fileLength || lastOffset > fileLength) {
                throw new IOException("Element offsets point outside of file length");
            }
            int crc = headerByteBuffer.getInt();
            if (crc != headerHash(version, fileLength, elementCount, (int)firstOffset, (int)lastOffset)) {
                throw new IOException("Queue file " + file + " was corrupted.");
            }
            first = readElement((int)firstOffset);
            readElement((int)lastOffset, last);
        } else {
            setLength(MINIMUM_SIZE);
            updateBufferExtent();
            headerByteBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, QueueFile.HEADER_LENGTH);
            elementCount = 0;
            first = new Element();
            writeHeader();
        }
        byteBuffer.clear();
    }

    /**
     * Writes QueueFile header.
     */
    private void writeHeader() {
        // first write all variables to a single byte buffer
        intToBytes(VERSIONED_HEADER, queueHeaderBuffer, 0);
        longToBytes(fileLength, queueHeaderBuffer, 4);
        intToBytes(elementCount, queueHeaderBuffer, 12);
        longToBytes(first.position, queueHeaderBuffer, 16);
        longToBytes(last.position, queueHeaderBuffer, 24);
        int crc = headerHash(VERSIONED_HEADER, fileLength, elementCount, first.position,
                last.position);
        intToBytes(crc, queueHeaderBuffer, 32);

        // then write the byte buffer out in one go
        headerByteBuffer.position(0);
        headerByteBuffer.put(queueHeaderBuffer, 0, QueueFile.HEADER_LENGTH);
        headerByteBuffer.force();
    }

    private int headerHash(int version, int length, int count, int firstPosition, int lastPosition) {
        int result = version;
        result = 31 * result + length;
        result = 31 * result + count;
        result = 31 * result + firstPosition;
        result = 31 * result + lastPosition;
        return result;
    }


    private void readElement(int position, Element elementToUpdate) throws IOException {
        if (position == 0) {
            elementToUpdate.length = 0;
        } else {
            ringRead(position, elementHeaderBuffer, 0, Element.HEADER_LENGTH);
            int length = bytesToInt(elementHeaderBuffer, 0);

            if (elementHeaderBuffer[4] != crc(length)) {
                logger.error("Failed to verify {}: crc {} does not match stored checksum {}. QueueFile is corrupted", elementToUpdate, crc(elementToUpdate.length), elementHeaderBuffer[4]);
                close();
                throw new IOException("Element is not correct; queue file is corrupted");
            }
            elementToUpdate.length = length;
        }
        elementToUpdate.position = position;
    }

    private Element readElement(int position) throws IOException {
        Element result = new Element();
        readElement(position, result);
        return result;
    }

    /** Wraps the position if it exceeds the end of the file. */
    private int wrapPosition(long position) {
        return (int)(position < fileLength ? position
                : QueueFile.HEADER_LENGTH + position - fileLength);
    }

    /**
     * Reads count bytes into buffer from file. Wraps if necessary.
     *
     * @param position in file to read from
     * @param buffer to read into
     * @param count # of bytes to read
     */
    private void ringRead(int position, byte[] buffer, int offset, int count) {
        position = wrapPosition(position);
        byteBuffer.position(position);
        if (position + count <= fileLength) {
            byteBuffer.get(buffer, offset, count);
        } else {
            // The read overlaps the EOF.
            // # of bytes to read before the EOF. Guaranteed to be less than Integer.MAX_VALUE.
            int firstPart = fileLength - position;
            byteBuffer.get(buffer, offset, firstPart);
            byteBuffer.position(QueueFile.HEADER_LENGTH);
            byteBuffer.get(buffer, offset + firstPart, count - firstPart);
        }
        byteBuffer.clear();
    }

    /**
     * Adds an element to the end of the queue.
     */
    public QueueFileOutputStream elementOutputStream() throws IOException {
        requireNotClosed();
        return new QueueFileOutputStream(last.nextPosition());
    }

    /** Number of bytes used in the file. */
    public int usedBytes() {
        if (elementCount == 0) {
            return QueueFile.HEADER_LENGTH;
        }

        if (last.position >= first.position) {
            // Contiguous queue.
            return (int)(last.nextPosition() - first.position) + QueueFile.HEADER_LENGTH;
        } else {
            // tail < head. The queue wraps.
            return (int)(last.nextPosition() - first.position) + fileLength;
        }
    }

    /** Returns true if this queue contains no entries. */
    public boolean isEmpty() {
        return elementCount == 0;
    }

    /** Sets the length of the file. */
    private void setLength(int newLength) throws IOException {
        if (newLength < usedBytes()) {
            throw new IllegalArgumentException("Cannot decrease size to less than the size used");
        }
        // Set new file length (considered metadata) and sync it to storage.
        fileLength = newLength;
        randomAccessFile.setLength(newLength);
        channel.force(true);
    }

    /** Call after a resize to update the buffer to the new file size */
    private void updateBufferExtent() throws IOException {
        byteBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileLength);
    }

    /** Returns an InputStream to read the eldest element. Returns null if the queue is empty. */
    public InputStream peek() throws IOException {
        requireNotClosed();
        if (isEmpty()) {
            return null;
        }
        return new QueueFileInputStream(first);
    }

    /**
     * Returns an iterator over elements in this QueueFile.
     *
     * <p>The iterator disallows modifications to be made to the QueueFile during iteration. Removing
     * elements from the head of the QueueFile is permitted during iteration using
     * {@link Iterator#remove()}.
     *
     * <p>The iterator may throw an unchecked {@link RuntimeException} {@link Iterator#remove()}.
     */
    @Override
    public Iterator<InputStream> iterator() {
        return new ElementIterator();
    }

    private final class ElementIterator implements Iterator<InputStream> {
        /** Index of element to be returned by subsequent call to next. */
        private int nextElementIndex;

        /** Position of element to be returned by subsequent call to next. */
        private int nextElementPosition;

        /**
         * The {@link #modCount} value that the iterator believes that the backing QueueFile should
         * have. If this expectation is violated, the iterator has detected concurrent modification.
         */
        private int expectedModCount;

        private final Element current;

        private ElementIterator() {
            nextElementIndex = 0;
            nextElementPosition = first.position;
            expectedModCount = modCount;
            current = new Element();
        }

        private void checkForComodification() {
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            checkForComodification();
            return nextElementIndex != elementCount;
        }

        @Override
        public InputStream next() {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            checkForComodification();
            if (nextElementIndex >= elementCount) {
                throw new NoSuchElementException();
            }

            try {
                readElement(nextElementPosition, current);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read element", ex);
            }
            InputStream in = new QueueFileInputStream(current);

            // Update the pointer to the next element.
            nextElementPosition = wrapPosition(current.nextPosition());
            nextElementIndex++;

            // Return the read element.
            return in;
        }

        /** Removal is not supported */
        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return "QueueFile<" + fileName
                    + ">[position=" + nextElementPosition
                    + ", index=" + nextElementIndex + "]";
        }
    }

    /** Returns the number of elements in this queue. */
    public int size() {
        return elementCount;
    }

    /** File size in bytes */
    public long fileSize() {
        return fileLength;
    }

    /**
     * Removes the eldest {@code n} elements.
     *
     * @throws NoSuchElementException if more than the available elements are requested to be removed
     */
    public void remove(int n) throws IOException {
        requireNotClosed();
        if (n < 0) {
            throw new IllegalArgumentException("Cannot remove negative (" + n + ") number of elements.");
        }
        if (n == 0) {
            return;
        }
        if (n == elementCount) {
            clear();
            return;
        }
        if (n > elementCount) {
            throw new NoSuchElementException(
                    "Cannot remove more elements (" + n + ") than present in queue (" + elementCount + ").");
        }

        // Read the position and length of the new first element.
        Element newFirst = new Element();
        newFirst.update(first);
        for (int i = 0; i < n; i++) {
            readElement(wrapPosition(newFirst.nextPosition()), newFirst);
        }

        // Commit the header.
        elementCount -= n;
        modCount++;
        first = newFirst;
        writeHeader();

        // truncate file if a lot of space is empty and no copy operations are needed
        if (last.position >= first.position && last.nextPosition() <= maxSize) {
            int newLength = fileLength;
            int goalLength = newLength / 2;
            int bytesUsed = usedBytes();
            int maxExtent = (int)last.nextPosition();

            while (goalLength >= QueueFile.MINIMUM_SIZE
                    && maxExtent <= goalLength
                    && bytesUsed <= goalLength / 2) {
                newLength = goalLength;
                goalLength /= 2;
            }
            if (newLength < fileLength) {
                logger.debug("Truncating {} from {} to {}", this, fileLength, newLength);
                setLength(newLength);
                updateBufferExtent();
            }
        }

    }

    /** Clears this queue. Truncates the file to the initial size. */
    public void clear() throws IOException {
        requireNotClosed();

        elementCount = 0;
        first.reset();
        last.reset();

        if (fileLength != MINIMUM_SIZE) {
            setLength(MINIMUM_SIZE);
            updateBufferExtent();
        }

        // Commit the header.
        writeHeader();

        modCount++;
    }

    private void requireNotClosed() throws IOException {
        if (closed) {
            throw new IOException("closed");
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        byteBuffer = null;
        channel.close();
        randomAccessFile.close();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "<" + fileName + ">"
                + "[length=" + fileLength
                + ", size=" + elementCount
                + ", first=" + first
                + ", last=" + last
                + "]";
    }

    /** A pointer to an element. */
    private static class Element {
        /** Length of element header in bytes. */
        private static final int HEADER_LENGTH = 5;

        /** Position in file. */
        private int position;

        /** The length of the data. */
        private int length;

        /**
         * Constructs a new element.
         *
         * @param position within file
         * @param length of data
         */
        private Element(int position, int length) {
            this.position = position;
            this.length = length;
        }

        private Element() {
            this(0, 0);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName()
                    + "[position=" + position
                    + ", length=" + length
                    + "]";
        }

        public void update(Element element) {
            this.position = element.position;
            this.length = element.length;
        }

        public long dataPosition() {
            if (position == 0) {
                throw new IllegalStateException("Cannot get data position of empty element");
            }
            return (long)position + Element.HEADER_LENGTH;
        }

        public long nextPosition() {
            if (position == 0) {
                return QueueFile.HEADER_LENGTH;
            } else {
                return (long)position + Element.HEADER_LENGTH + length;
            }
        }

        public void reset() {
            position = 0;
            length = 0;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) return true;
            if (other == null || getClass() != other.getClass()) return false;

            Element otherElement = (Element)other;
            return position == otherElement.position && length == otherElement.length;
        }

        @Override
        public int hashCode() {
            return 31 * position + length;
        }
    }

    private static byte crc(int value) {
        byte result = 17;
        result = (byte)(31 * result + (value >> 24) & 0xFF);
        result = (byte)(31 * result + ((value >> 16) & 0xFF));
        result = (byte)(31 * result + ((value >> 8) & 0xFF));
        result = (byte)(31 * result + (value & 0xFF));
        return result;
    }

    private class QueueFileInputStream extends InputStream {
        private final int totalLength;
        private final ByteBuffer buffer;
        private final int expectedModCount;
        private int bytesRead;

        public QueueFileInputStream(Element element) {
            logger.trace("QueueFileInputStream for element {}", element);
            this.buffer = byteBuffer.duplicate();
            this.buffer.position(wrapPosition(element.dataPosition()));
            this.totalLength = element.length;
            this.expectedModCount = modCount;
            bytesRead = 0;
        }

        @Override
        public int available() {
            return totalLength - bytesRead;
        }

        @Override
        public long skip(long byteCount) {
            int countAvailable = (int)Math.min(byteCount, totalLength - bytesRead);
            bytesRead += countAvailable;
            this.buffer.position(wrapPosition(this.buffer.position() + countAvailable));
            return countAvailable;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead == totalLength) {
                return -1;
            }
            checkForComodification();

            // wrap position
            if (buffer.position() == fileLength) {
                buffer.position(QueueFile.HEADER_LENGTH);
            }
            bytesRead++;
            return buffer.get() & 0xFF;
        }

        @Override
        public int read(@NonNull byte[] bytes, int offset, int length) throws IOException {
            checkOffsetAndCount(bytes, offset, length);
            if (bytesRead == totalLength) {
                return -1;
            }
            checkForComodification();

            int lengthAvailable = Math.min(length, totalLength - bytesRead);

            int linearPart = fileLength - buffer.position();
            if (linearPart < lengthAvailable) {
                if (linearPart > 0) {
                    buffer.get(bytes, offset, linearPart);
                }
                buffer.position(QueueFile.HEADER_LENGTH);
                buffer.get(bytes, offset + linearPart, lengthAvailable - linearPart);
            } else {
                buffer.get(bytes, offset, lengthAvailable);
            }
            bytesRead += lengthAvailable;
            return lengthAvailable;
        }

        private void checkForComodification() throws IOException {
            if (modCount != expectedModCount) {
                throw new IOException("Buffer modified while reading InputStream");
            }
        }

        @Override
        public String toString() {
            return "QueueFileInputStream<" + fileName + ">[position=" + buffer.position() + ",length=" + totalLength + ",bytesRead=" + bytesRead + "]";
        }
    }

    /**
     * An OutputStream that can write multiple elements. After finished writing one element, call
     * {@link #nextElement()} to start writing the next.
     *
     * <p>It is very important to close this OutputStream, as this is the only way that the data is
     * actually committed to file.
     */
    public class QueueFileOutputStream extends OutputStream {
        private ByteBuffer buffer;
        private Element current;
        private boolean closed;
        private Element newLast;
        private Element newFirst;
        private int elementsWritten;
        private long streamBytesUsed;

        private QueueFileOutputStream(long position) throws IOException {
            this.buffer = byteBuffer.duplicate();
            this.current = new Element(wrapPosition(position), 0);
            this.buffer.position(current.position);
            closed = false;
            newLast = null;
            newFirst = null;
            elementsWritten = 0;
            streamBytesUsed = 0L;
        }

        @Override
        public void write(int byteValue) throws IOException {
            checkConditions();
            writeNullElementIfNeeded();
            expandAndUpdate(1);

            buffer.put((byte)(byteValue & 0xFF));
            current.length++;

            // wrap position
            if (buffer.position() == fileLength) {
                buffer.position(QueueFile.HEADER_LENGTH);
            }
        }

        @Override
        public void write(@NonNull byte[] bytes, int offset, int length) throws IOException {
            checkOffsetAndCount(bytes, offset, length);
            if (length == 0) {
                return;
            }
            checkConditions();
            writeNullElementIfNeeded();
            expandAndUpdate(length);

            ringWrite(bytes, offset, length);
            current.length += length;

            // wrap position
            if (buffer.position() == fileLength) {
                buffer.position(QueueFile.HEADER_LENGTH);
            }
        }

        private void checkConditions() throws IOException {
            if (closed || QueueFile.this.closed) {
                throw new IOException("closed");
            }
        }

        private void writeNullElementIfNeeded() throws IOException {
            if (current.length != 0) {
                return;
            }
            expandAndUpdate(Element.HEADER_LENGTH);
            Arrays.fill(elementHeaderBuffer, (byte)0);
            ringWrite(elementHeaderBuffer, 0, Element.HEADER_LENGTH);
        }

        /**
         * Proceed writing the next element. Zero length elements are not written, so always write
         * at least one byte to store an element.
         */
        public void nextElement() throws IOException {
            checkConditions();
            if (current.length == 0) {
                return;
            }
            newLast = current;
            if (newFirst == null && isEmpty()) {
                newFirst = current;
            }

            current = new Element(buffer.position(), 0);

            buffer.position(newLast.position);
            intToBytes(newLast.length, elementHeaderBuffer, 0);
            elementHeaderBuffer[4] = crc(newLast.length);
            ringWrite(elementHeaderBuffer, 0, Element.HEADER_LENGTH);

            buffer.position(current.position);
            elementsWritten++;
        }

        public long bytesNeeded() {
            return QueueFile.this.usedBytes() + streamBytesUsed;
        }

        /**
         * Increase the number of bytes used.
         * @param length number of bytes to increase the used bytes with
         * @return number of bytes currently used
         */

        private int increaseBytesUsed(int length) throws IOException {
            streamBytesUsed += length;

            long bytesNeeded = usedBytes() + streamBytesUsed;
            if (bytesNeeded > maxSize) {
                throw new IOException("Data does not fit in queue");
            }
            return (int)bytesNeeded;
        }

        /** Expands the storage if necessary, updating the buffer if needed. */
        private void expandAndUpdate(int length) throws IOException {
            int bytesNeeded = increaseBytesUsed(length);
            if (bytesNeeded <= fileLength) {
                return;
            }

            logger.debug("Extending {}", QueueFile.this);

            int oldLength = fileLength;
            // Double the length until we can fit the new data.
            long newLength = fileLength * 2;
            while (newLength < bytesNeeded) {
                newLength += newLength;
            }

            int position = buffer.position();
            int beginningOfFirstElement = newFirst == null ? first.position : newFirst.position;

            byteBuffer.force();
            setLength((int)Math.min(newLength, maxSize));

            // Calculate the position of the tail end of the data in the ring buffer
            // If the buffer is split, we need to make it contiguous
            if (position <= beginningOfFirstElement) {
                channel.position(oldLength); // destination position
                int count = position - QueueFile.HEADER_LENGTH;
                if (channel.transferTo(HEADER_LENGTH, count, channel) != count) {
                    throw new AssertionError("Copied insufficient number of bytes!");
                }
                modCount++;

                // Last position was moved forward in the copy
                int positionUpdate = oldLength - QueueFile.HEADER_LENGTH;
                if (last.position < beginningOfFirstElement) {
                    last.position += positionUpdate;
                }
                if (current.position <= beginningOfFirstElement) {
                    current.position += positionUpdate;
                }
                position += positionUpdate;
            }

            updateBufferExtent();
            buffer = byteBuffer.duplicate();

            // Commit the expansion.
            writeHeader();
            buffer.position(position);
        }

        /**
         * Closes the stream and commits it to file.
         * @throws IOException
         */
        @Override
        public void close() throws IOException {
            try {
                nextElement();
                if (elementsWritten > 0) {
                    if (newLast != null) {
                        last.update(newLast);
                    }
                    if (newFirst != null) {
                        first = newFirst;
                    }
                    elementCount += elementsWritten;
                    byteBuffer.force();
                    modCount++;
                    writeHeader();
                }
            } finally {
                closed = true;
            }
        }

        @Override
        public String toString() {
            return "QueueFileOutputStream<" + fileName + ">[current=" + current
                    + ",total=" + streamBytesUsed + ",used=" + bytesNeeded() + "]";
        }


        /**
         * Writes count bytes from buffer to position in file. Automatically wraps write if position is
         * past the end of the file or if buffer overlaps it.
         *
         * @param bytes to write from
         * @param count # of bytes to write
         */
        private void ringWrite(byte[] bytes, int offset, int count) throws IOException {
            int position = buffer.position();
            int linearPart = fileLength - position;
            if (linearPart >= count) {
                buffer.put(bytes, offset, count);
            } else {
                // The write overlaps the EOF.
                // # of bytes to write before the EOF. Guaranteed to be less than Integer.MAX_VALUE.
                if (linearPart > 0) {
                    buffer.put(bytes, offset, linearPart);
                }
                buffer.position(QueueFile.HEADER_LENGTH);
                buffer.put(bytes, offset + linearPart, count - linearPart);
            }
        }
    }

    private static void checkOffsetAndCount(byte[] bytes, int offset, int length) {
        if (offset < 0) {
            throw new IndexOutOfBoundsException("offset < 0");
        }
        if (length < 0) {
            throw new IndexOutOfBoundsException("length < 0");
        }
        if (offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    "extent of offset and length larger than buffer length");
        }
    }
}
