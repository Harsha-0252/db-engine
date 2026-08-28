package com.dbengine.storage;

import com.dbengine.common.Constants;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class DiskManager implements AutoCloseable {

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final AtomicLong numPages;

    public DiskManager(Path dbFilePath) throws IOException {
        this.file = new RandomAccessFile(dbFilePath.toFile(), "rw");
        this.channel = file.getChannel();
        this.numPages = new AtomicLong(file.length() / Constants.PAGE_SIZE);
    }

    public synchronized void readPage(PageId pageId, Page page) throws IOException {
        ByteBuffer buffer = page.getBuffer();
        buffer.clear();
        long fileOffset = pageId.toFileOffset();

        int totalRead = 0;
        while (totalRead < Constants.PAGE_SIZE) {
            int bytesRead = channel.read(buffer, fileOffset + totalRead);
            if (bytesRead == -1) {
                throw new IOException("Unexpected end of file reading " + pageId);
            }
            totalRead += bytesRead;
        }

        buffer.clear();
        page.setPageId(pageId);
        page.clearDirty();
    }

    public synchronized void writePage(PageId pageId, Page page) throws IOException {
        ByteBuffer buffer = page.getBuffer();
        buffer.clear();
        long fileOffset = pageId.toFileOffset();

        int totalWritten = 0;
        while (totalWritten < Constants.PAGE_SIZE) {
            int bytesWritten = channel.write(buffer, fileOffset + totalWritten);
            totalWritten += bytesWritten;
        }

        channel.force(false);
        page.clearDirty();
    }

    public synchronized PageId allocatePage() throws IOException {
        long newId = numPages.getAndIncrement();
        PageId pageId = new PageId(newId);

        ByteBuffer zeroPage = ByteBuffer.allocate(Constants.PAGE_SIZE);
        int totalWritten = 0;
        long fileOffset = pageId.toFileOffset();
        while (totalWritten < Constants.PAGE_SIZE) {
            int bytesWritten = channel.write(zeroPage, fileOffset + totalWritten);
            totalWritten += bytesWritten;
        }
        channel.force(false);

        return pageId;
    }

    public long getNumPages() {
        return numPages.get();
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
        file.close();
    }
}
