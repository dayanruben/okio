/*
 * Copyright (C) 2023 Square, Inc.
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
package okio

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator
import okio.Path.Companion.toPath
import okio.internal.ErrnoException
import okio.internal.fdClose
import okio.internal.preview1.Errno
import okio.internal.preview1.dirnamelen
import okio.internal.preview1.fd
import okio.internal.preview1.fd_readdir
import okio.internal.preview1.fdflags
import okio.internal.preview1.fdflags_append
import okio.internal.preview1.filetype
import okio.internal.preview1.filetype_directory
import okio.internal.preview1.filetype_regular_file
import okio.internal.preview1.filetype_symbolic_link
import okio.internal.preview1.oflag_creat
import okio.internal.preview1.oflag_directory
import okio.internal.preview1.oflag_excl
import okio.internal.preview1.oflag_trunc
import okio.internal.preview1.oflags
import okio.internal.preview1.path_create_directory
import okio.internal.preview1.path_filestat_get
import okio.internal.preview1.path_open
import okio.internal.preview1.path_readlink
import okio.internal.preview1.path_remove_directory
import okio.internal.preview1.path_rename
import okio.internal.preview1.path_symlink
import okio.internal.preview1.path_unlink_file
import okio.internal.preview1.right_fd_filestat_get
import okio.internal.preview1.right_fd_filestat_set_size
import okio.internal.preview1.right_fd_read
import okio.internal.preview1.right_fd_readdir
import okio.internal.preview1.right_fd_seek
import okio.internal.preview1.right_fd_sync
import okio.internal.preview1.right_fd_write
import okio.internal.preview1.rights
import okio.internal.readString
import okio.internal.write

/**
 * Use [WASI] to implement the Okio file system interface.
 *
 * [WASI]: https://wasi.dev/
 */
class WasiFileSystem(
  private val relativePathPreopen: Int = DEFAULT_FIRST_PREOPEN,
  pathToPreopen: Map<Path, Int> = mapOf("/".toPath() to DEFAULT_FIRST_PREOPEN),
) : FileSystem() {
  private val pathSegmentsToPreopen = pathToPreopen.mapKeys { (key, _) -> key.segmentsBytes }

  init {
    require(pathSegmentsToPreopen.isNotEmpty()) {
      "pathToPreopen must be non-empty"
    }
  }

  override fun canonicalize(path: Path): Path {
    // There's no APIs in preview1 to canonicalize a path. We give it a best effort by resolving
    // all symlinks, but this could result in a relative path.
    val candidate = resolveSymlinks(path, 0)

    if (!candidate.isAbsolute) {
      throw IOException("WASI preview1 cannot canonicalize relative paths")
    }

    return candidate
  }

  private fun resolveSymlinks(
    path: Path,
    recurseCount: Int = 0,
  ): Path {
    // 40 is chosen for consistency with the Linux kernel (which previously used 8).
    if (recurseCount > 40) throw IOException("symlink cycle?")

    val parent = path.parent
    val resolvedParent = when {
      parent != null -> resolveSymlinks(parent, recurseCount + 1)
      else -> null
    }
    val pathWithResolvedParent = when {
      resolvedParent != null -> resolvedParent / path.name
      else -> path
    }

    val symlinkTarget = metadata(pathWithResolvedParent).symlinkTarget
      ?: return pathWithResolvedParent

    val resolvedSymlinkTarget = when {
      symlinkTarget.isAbsolute -> symlinkTarget
      resolvedParent != null -> resolvedParent / symlinkTarget
      else -> symlinkTarget
    }

    return resolveSymlinks(resolvedSymlinkTarget, recurseCount + 1)
  }

  override fun metadataOrNull(path: Path): FileMetadata? {
    withScopedMemoryAllocator { allocator ->
      val returnPointer = allocator.allocate(64)
      val (pathAddress, pathSize) = allocator.write(path.toString())

      val errno = path_filestat_get(
        fd = preopenFd(path) ?: return null,
        flags = 0,
        path = pathAddress.address.toInt(),
        pathSize = pathSize,
        returnPointer = returnPointer.address.toInt(),
      )

      when (errno) {
        Errno.notcapable.ordinal -> {
          // Both of these paths return `notcapable`:
          //  * The root, '/', which is a parent of real paths.
          //  * Non-existent paths like '/127.0.0.1/../localhost/c$/Windows', which don't matter.
          // Treat the root path as special.
          if (path.isRoot) return FileMetadata(isDirectory = true)
          return null
        }
        Errno.noent.ordinal -> return null
      }

      if (errno != 0) throw ErrnoException(errno.toShort())

      // Skip device, offset 0.
      // Skip ino, offset 8.
      val filetype: filetype = (returnPointer + 16).loadByte()
      // Skip nlink, offset 24.
      val filesize: Long = (returnPointer + 32).loadLong()
      val atim: Long = (returnPointer + 40).loadLong() // Access time, Nanoseconds.
      val mtim: Long = (returnPointer + 48).loadLong() // Modification time, Nanoseconds.
      val ctim: Long = (returnPointer + 56).loadLong() // Status change time, Nanoseconds.

      val symlinkTarget: Path? = when (filetype) {
        filetype_symbolic_link -> {
          val bufLen = filesize.toInt() + 1
          val bufPointer = allocator.allocate(bufLen)
          val readlinkReturnPointer = allocator.allocate(4) // `size` is u32, 4 bytes.
          val readlinkErrno = path_readlink(
            fd = preopenFd(path) ?: return null,
            path = pathAddress.address.toInt(),
            pathSize = pathSize,
            buf = bufPointer.address.toInt(),
            buf_len = bufLen,
            returnPointer = readlinkReturnPointer.address.toInt(),
          )
          if (readlinkErrno != 0) throw ErrnoException(readlinkErrno.toShort())
          val symlinkSize = readlinkReturnPointer.loadInt()
          val symlink = bufPointer.readString(symlinkSize)
          symlink.toPath()
        }

        else -> null
      }

      return FileMetadata(
        isRegularFile = filetype == filetype_regular_file,
        isDirectory = filetype == filetype_directory,
        symlinkTarget = symlinkTarget,
        size = filesize,
        createdAtMillis = ctim / 1_000_000L, // Nanos to millis.
        lastModifiedAtMillis = mtim / 1_000_000L, // Nanos to millis.
        lastAccessedAtMillis = atim / 1_000_000L, // Nanos to millis.
      )
    }
  }

  override fun list(dir: Path): List<Path> {
    val fd = pathOpen(
      path = dir,
      oflags = oflag_directory,
      rightsBase = right_fd_readdir,
    )
    try {
      return list(dir, fd)
    } finally {
      fdClose(fd)
    }
  }

  override fun listOrNull(dir: Path): List<Path>? {
    // TODO: stop using exceptions for flow control.
    try {
      return list(dir)
    } catch (e: FileNotFoundException) {
      return null
    } catch (e: ErrnoException) {
      if (e.errno == Errno.notdir) return null
      throw e
    }
  }

  private fun list(dir: Path, fd: fd): List<Path> {
    withScopedMemoryAllocator { allocator ->
      // In theory, fd_readdir uses a 'cookie' field to page through results. In practice the
      // NodeJS implementation doesn't honor the cookie and directories with large file names
      // don't progress. Instead, just grow the buffer until the entire directory fits.
      var bufSize = 2048
      var bufPointer = allocator.allocate(bufSize)
      val returnPointer = allocator.allocate(4) // `size` is u32, 4 bytes.
      var pageSize: Int
      while (true) {
        val errno = fd_readdir(
          fd = fd,
          buf = bufPointer.address.toInt(),
          buf_len = bufSize,
          cookie = 0L, // Don't bother with dircookie, it doesn't work for large file names.
          returnPointer = returnPointer.address.toInt(),
        )

        if (errno != 0) throw ErrnoException(errno.toShort())
        pageSize = returnPointer.loadInt()

        if (pageSize < bufSize) break

        bufSize *= 4
        bufPointer = allocator.allocate(bufSize)
      }

      // Parse dirent records from the buffer.
      var pos = bufPointer
      val limit = bufPointer + pageSize
      val result = mutableListOf<Path>()
      while (pos.address < limit.address) {
        pos += 8 // Skip dircookie.
        pos += 8 // Skip inode.
        val d_namelen: dirnamelen = pos.loadInt()
        pos += 4 // Consume d_namelen.
        pos += 4 // Skip d_type.

        val name = pos.readString(d_namelen)
        pos += d_namelen

        result += dir / name
      }

      result.sort()
      return result
    }
  }

  override fun openReadOnly(file: Path): FileHandle {
    val rightsBase = right_fd_filestat_get or
      right_fd_read or
      right_fd_seek or
      right_fd_sync
    val fd = pathOpen(
      path = file,
      oflags = 0,
      rightsBase = rightsBase,
    )
    return WasiFileHandle(fd, readWrite = false)
  }

  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
    val oflags = when {
      mustCreate && mustExist -> {
        throw IllegalArgumentException("Cannot require mustCreate and mustExist at the same time.")
      }
      mustCreate -> oflag_creat or oflag_excl
      mustExist -> 0
      else -> oflag_creat
    }
    val rightsBase = right_fd_filestat_get or
      right_fd_filestat_set_size or
      right_fd_read or
      right_fd_seek or
      right_fd_sync or
      right_fd_write
    val fd = pathOpen(
      path = file,
      oflags = oflags,
      rightsBase = rightsBase,
    )
    return WasiFileHandle(fd, readWrite = true)
  }

  override fun source(file: Path): Source {
    return FileSource(
      fd = pathOpen(
        path = file,
        oflags = 0,
        rightsBase = right_fd_read,
      ),
    )
  }

  override fun sink(file: Path, mustCreate: Boolean): Sink {
    val oflags = when {
      mustCreate -> oflag_creat or oflag_excl or oflag_trunc
      else -> oflag_creat or oflag_trunc
    }

    return FileSink(
      fd = pathOpen(
        path = file,
        oflags = oflags,
        rightsBase = right_fd_write or right_fd_sync,
      ),
    )
  }

  override fun appendingSink(file: Path, mustExist: Boolean): Sink {
    val oflags = when {
      mustExist -> 0
      else -> oflag_creat
    }

    return FileSink(
      fd = pathOpen(
        path = file,
        oflags = oflags,
        rightsBase = right_fd_write,
        fdflags = fdflags_append,
      ),
    )
  }

  override fun createDirectory(dir: Path, mustCreate: Boolean) {
    withScopedMemoryAllocator { allocator ->
      val (pathAddress, pathSize) = allocator.write(dir.toString())

      val errno = path_create_directory(
        fd = preopenFd(dir) ?: throw FileNotFoundException("no preopen: $dir"),
        path = pathAddress.address.toInt(),
        pathSize = pathSize,
      )
      if (errno == Errno.exist.ordinal) {
        if (mustCreate) throw IOException("already exists: $dir")
        return
      }

      if (errno != 0) throw ErrnoException(errno.toShort())
    }
  }

  override fun atomicMove(source: Path, target: Path) {
    withScopedMemoryAllocator { allocator ->
      val (sourcePathAddress, sourcePathSize) = allocator.write(source.toString())
      val (targetPathAddress, targetPathSize) = allocator.write(target.toString())

      val errno = path_rename(
        fd = preopenFd(source) ?: throw FileNotFoundException("no preopen: $source"),
        old_path = sourcePathAddress.address.toInt(),
        old_pathSize = sourcePathSize,
        new_fd = preopenFd(target) ?: throw FileNotFoundException("no preopen: $target"),
        new_path = targetPathAddress.address.toInt(),
        new_pathSize = targetPathSize,
      )
      if (errno == Errno.noent.ordinal) {
        throw FileNotFoundException("no such file: $source")
      }

      if (errno != 0) throw ErrnoException(errno.toShort())
    }
  }

  override fun delete(path: Path, mustExist: Boolean) {
    withScopedMemoryAllocator { allocator ->
      val (pathAddress, pathSize) = allocator.write(path.toString())
      val preopenFd = preopenFd(path) ?: throw FileNotFoundException("no preopen: $path")

      var errno = path_unlink_file(
        fd = preopenFd,
        path = pathAddress.address.toInt(),
        pathSize = pathSize,
      )
      // If unlink failed, try remove_directory.
      when (errno) {
        Errno.noent.ordinal -> {
          if (mustExist) throw FileNotFoundException("no such file: $path")
          return // Nothing to delete.
        }

        Errno.perm.ordinal,
        Errno.isdir.ordinal,
        -> {
          errno = path_remove_directory(
            fd = preopenFd,
            path = pathAddress.address.toInt(),
            pathSize = pathSize,
          )
        }
      }
      if (errno != 0) throw ErrnoException(errno.toShort())
    }
  }

  override fun createSymlink(source: Path, target: Path) {
    withScopedMemoryAllocator { allocator ->
      val (sourcePathAddress, sourcePathSize) = allocator.write(source.toString())
      val (targetPathAddress, targetPathSize) = allocator.write(target.toString())

      val errno = path_symlink(
        old_path = targetPathAddress.address.toInt(),
        old_pathSize = targetPathSize,
        fd = preopenFd(source) ?: throw FileNotFoundException("no preopen: $source"),
        new_path = sourcePathAddress.address.toInt(),
        new_pathSize = sourcePathSize,
      )
      if (errno != 0) throw ErrnoException(errno.toShort())
    }
  }

  private fun pathOpen(
    path: Path,
    oflags: oflags,
    rightsBase: rights,
    fdflags: fdflags = 0,
  ): fd {
    withScopedMemoryAllocator { allocator ->
      val preopenFd = preopenFd(path) ?: throw FileNotFoundException("no preopen: $path")
      val (pathAddress, pathSize) = allocator.write(path.toString())

      val returnPointer: Pointer = allocator.allocate(4) // fd is u32.
      val errno = path_open(
        fd = preopenFd,
        dirflags = 0,
        path = pathAddress.address.toInt(),
        pathSize = pathSize,
        oflags = oflags,
        fs_rights_base = rightsBase,
        fs_rights_inheriting = 0,
        fdflags = fdflags,
        returnPointer = returnPointer.address.toInt(),
      )
      if (errno == Errno.noent.ordinal) {
        throw FileNotFoundException("no such file: $path")
      }
      if (errno != 0) throw ErrnoException(errno.toShort())
      return returnPointer.loadInt()
    }
  }

  /**
   * Returns the file descriptor of the preopened path that is an ancestor of [path]. Returns null
   * if there is no such file descriptor.
   */
  private fun preopenFd(path: Path): fd? {
    if (path.isRelative) return relativePathPreopen

    val pathSegmentsBytes = path.segmentsBytes
    for ((candidate, fd) in pathSegmentsToPreopen) {
      if (pathSegmentsBytes.size < candidate.size) continue
      if (pathSegmentsBytes.subList(0, candidate.size) != candidate) continue
      return fd
    }
    return null
  }

  override fun toString() = "okio.WasiFileSystem"

  companion object {
    /**
     * File descriptor of the first preopen in the `WASI` instance's configured `preopens` property.
     * This is 3 by default, assuming `stdin` is 0, `stdout` is 1, and `stderr` is 2.
     *
     * Other preopens are assigned sequentially starting at this value.
     */
    val DEFAULT_FIRST_PREOPEN = 3
  }
}
