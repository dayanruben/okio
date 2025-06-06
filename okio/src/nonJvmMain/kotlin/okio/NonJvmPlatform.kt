/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package okio

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import okio.internal.commonAsUtf8ToByteArray
import okio.internal.commonToUtf8String

internal expect val PLATFORM_DIRECTORY_SEPARATOR: String

internal actual fun ByteArray.toUtf8String(): String = commonToUtf8String()

internal actual fun String.asUtf8ToByteArray(): ByteArray = commonAsUtf8ToByteArray()

actual open class ArrayIndexOutOfBoundsException actual constructor(
  message: String?,
) : IndexOutOfBoundsException(message)

actual class Lock {
  companion object {
    val instance = Lock()
  }
}

internal actual fun newLock(): Lock = Lock.instance

actual inline fun <T> Lock.withLock(action: () -> T): T {
  contract {
    callsInPlace(action, InvocationKind.EXACTLY_ONCE)
  }

  return action()
}

actual open class IOException actual constructor(
  message: String?,
  cause: Throwable?,
) : Exception(message, cause) {
  actual constructor(message: String?) : this(message, null)
  actual constructor() : this(null, null)
}

actual class ProtocolException actual constructor(message: String) : IOException(message)

actual open class EOFException actual constructor(message: String?) : IOException(message) {
  actual constructor() : this(null)
}

actual open class FileNotFoundException actual constructor(message: String?) : IOException(message)

actual interface Closeable {
  @Throws(IOException::class)
  actual fun close()
}
