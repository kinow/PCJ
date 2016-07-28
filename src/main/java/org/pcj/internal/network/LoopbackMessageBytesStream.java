/*
 * This file is the internal part of the PCJ Library
 */
package org.pcj.internal.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.pcj.internal.Configuration;
import org.pcj.internal.message.Message;

/**
 *
 * @author Marek Nowicki (faramir@mat.umk.pl)
 */
public class LoopbackMessageBytesStream implements AutoCloseable {

    private final Message message;
    private final Queue<ByteBuffer> queue;
    private final MessageDataOutputStream messageDataOutputStream;
    private final MessageDataInputStream messageDataInputStream;

    public LoopbackMessageBytesStream(Message message) {
        this(message, Configuration.CHUNK_SIZE);
    }

    public LoopbackMessageBytesStream(Message message, short chunkSize) {
        this.message = message;

        this.queue = new ConcurrentLinkedQueue<>();
        this.messageDataOutputStream = new MessageDataOutputStream(new LoopbackOutputStream(queue, chunkSize));
        this.messageDataInputStream = new MessageDataInputStream(new LoopbackInputStream(queue));
    }

    public void writeMessage() throws IOException {
        message.write(messageDataOutputStream);
    }

    public MessageDataInputStream getMessageData() {
        return messageDataInputStream;
    }

    @Override
    public void close() throws IOException {
        messageDataOutputStream.close();
        messageDataInputStream.close();
    }

    private static class LoopbackOutputStream extends OutputStream {

        private final short chunkSize;
        private final Queue<ByteBuffer> queue;
        volatile private boolean closed;
        private ByteBuffer currentByteBuffer;

        public LoopbackOutputStream(Queue<ByteBuffer> queue, short chunkSize) {
            this.chunkSize = chunkSize;
            this.queue = queue;
            this.closed = false;

            allocateBuffer();
        }

        private void allocateBuffer() {
            currentByteBuffer = ByteBuffer.allocate(chunkSize);
        }

        @Override
        public void flush() {
            if (currentByteBuffer.position() <= 0) {
                return;
            }
            currentByteBuffer.flip();
            queue.offer(currentByteBuffer);

            allocateBuffer();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (currentByteBuffer.position() > 0) {
                flush();
            }
            currentByteBuffer = null;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void write(int b) {
            currentByteBuffer.put((byte) b);
            if (currentByteBuffer.hasRemaining() == false) {
                flush();
            }
        }

        @Override
        public void write(byte[] b) {
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }
    }

    private static class LoopbackInputStream extends InputStream {

        private final Queue<ByteBuffer> queue;
        volatile private boolean closed;

        public LoopbackInputStream(Queue<ByteBuffer> queue) {
            this.queue = queue;
            this.closed = false;
        }

        @Override
        public void close() {
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public int read() {
            while (true) {
                ByteBuffer byteBuffer = queue.peek();
                if (byteBuffer == null) {
                    if (closed) {
                        return -1;
                    } else {
                        throw new IllegalStateException("Stream not closed, but no more data available.");
                    }
                } else if (byteBuffer.hasRemaining() == false) {
                    queue.poll();
                } else {
                    return byteBuffer.get() & 0xFF;
                }
            }
        }

        @Override
        public int read(byte[] b) {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }

            int i = 0;
            while (true) {
                ByteBuffer byteBuffer = queue.peek();
                if (byteBuffer == null) {
                    if (closed) {
                        if (i == 0) {
                            return -1;
                        } else {
                            return i;
                        }
                    } else {
                        throw new IllegalStateException("Stream not closed, but no more data available.");
                    }
                } else if (byteBuffer.hasRemaining() == false) {
                    queue.poll();
                } else {
                    while (byteBuffer.hasRemaining()) {
                        b[off + i] = byteBuffer.get();
                        if (++i == len) {
                            return len;
                        }
                    }
                }
            }
        }
    }
}
