package com.m3958.visitrank.unit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.OverlappingFileLockException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class FileLockTest {
  
  private FileChannel readOnlyChannel;

  private FileChannel writeOnlyChannel;

  private FileChannel readWriteChannel;

  private final String CONTENT = "The best things in life are nearest: Breath in your nostrils, light in your eyes, "
          + "flowers at your feet, duties at your hand, the path of right just before you. Then do not grasp at the stars, "
          + "but do life's plain, common work as it comes, certain that daily duties and daily bread are the sweetest "
          + " things in life.--Robert Louis Stevenson";

  @Before
  public void setUp() throws Exception {
      // Create a three temporary files with content.
      File[] tempFiles = new File[3];
      for (int i = 0; i < tempFiles.length; i++) {
          tempFiles[i] = File.createTempFile("testing", "tmp");
          tempFiles[i].deleteOnExit();
          FileWriter writer = new FileWriter(tempFiles[i]);
          writer.write(CONTENT);
          writer.close();
      }

      // Open read, write, and read/write channels on the temp files.
      FileInputStream fileInputStream = new FileInputStream(tempFiles[0]);
      readOnlyChannel = fileInputStream.getChannel();

      FileOutputStream fileOutputStream = new FileOutputStream(tempFiles[1]);
      writeOnlyChannel = fileOutputStream.getChannel();

      RandomAccessFile randomAccessFile = new RandomAccessFile(tempFiles[2],
              "rw");
      readWriteChannel = randomAccessFile.getChannel();
  }

  @Test
  public void test_illegalLocks() throws IOException {
      // Cannot acquire an exclusive lock on a read-only file channel
      try {
          readOnlyChannel.lock();
          Assert.fail("Acquiring a full exclusive lock on a read only channel should fail.");
      } catch (NonWritableChannelException ex) {
          // Expected.
      }
      // Cannot get a shared lock on a write-only file channel.
      try {
          writeOnlyChannel.lock(1, 10, true);
          Assert.fail("Acquiring a shared lock on a write-only channel should fail.");
      } catch (NonReadableChannelException ex) {
          // expected
      }
  }

  @Test
  public void test_lockReadWrite() throws IOException {
      // Acquire an exclusive lock across the entire file.
      FileLock flock = readWriteChannel.lock();
      if (flock != null) {
          flock.release();
      }
  }

  @Test
  public void test_illegalLockParameters() throws IOException {
      // Cannot lock negative positions
      try {
          readOnlyChannel.lock(-1, 10, true);
          Assert.fail("Passing illegal args to lock should fail.");
      } catch (IllegalArgumentException ex) {
          // expected
      }
      try {
          writeOnlyChannel.lock(-1, 10, false);
          Assert.fail("Passing illegal args to lock should fail.");
      } catch (IllegalArgumentException ex) {
          // expected
      }
      try {
          readWriteChannel.lock(-1, 10, false);
          Assert.fail("Passing illegal args to lock should fail.");
      } catch (IllegalArgumentException ex) {
          // expected
      }

      // Lock a range at the front, shared.
      FileLock flock1 = readWriteChannel.lock(22, 110, true);

      // Try to acquire an overlapping lock.
      try {
          readWriteChannel.lock(75, 210, true);
      } catch (OverlappingFileLockException exception) {
          // expected
          flock1.release();
      }
  }

  @Test
  public void test_lockLLZ() throws IOException {
      // Lock a range at the front, non-shared.
      FileLock flock1 = readWriteChannel.lock(0, 10, false);

      // Lock a shared range further in the same file.
      FileLock flock2 = readWriteChannel.lock(22, 100, true);

      // The spec allows the impl to refuse shared locks
      flock1.release();
      flock2.release();
  }

  @Test
  public void test_tryLock() throws IOException {
      try {
          readOnlyChannel.tryLock();
          Assert.fail("Acquiring a full exclusive lock on a read channel should have thrown an exception.");
      } catch (NonWritableChannelException ex) {
          // Expected.
      }
  }

  @Test
  public void test_tryLockLLZ() throws IOException {
      // It is illegal to request an exclusive lock on a read-only channel
      try {
          readOnlyChannel.tryLock(0, 99, false);
          Assert.fail("Acquiring exclusive lock on read-only channel should fail");
      } catch (NonWritableChannelException ex) {
          // Expected
      }

      // It is invalid to request a lock starting before the file start
      try {
          readOnlyChannel.tryLock(-99, 0, true);
          Assert.fail("Acquiring an illegal lock value should fail.");
      } catch (IllegalArgumentException ex) {
          // expected
      }

      // Acquire a valid lock
      FileLock tmpLock = readOnlyChannel.tryLock(0, 10, true);
      Assert.assertTrue(tmpLock.isValid());
      tmpLock.release();

      // Acquire another valid lock -- and don't release it yet
      FileLock lock = readOnlyChannel.tryLock(10, 788, true);
      Assert.assertTrue(lock.isValid());

      // Overlapping locks are illegal
      try {
          readOnlyChannel.tryLock(1, 23, true);
          Assert.fail("Acquiring an overlapping lock should fail.");
      } catch (OverlappingFileLockException ex) {
          // Expected
      }

      // Adjacent locks are legal
      FileLock adjacentLock = readOnlyChannel.tryLock(1, 3, true);
      Assert.assertTrue(adjacentLock.isValid());
      adjacentLock.release();

      // Release longer lived lock
      lock.release();
  }
}
