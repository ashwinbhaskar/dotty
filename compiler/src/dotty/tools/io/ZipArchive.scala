/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Paul Phillips
 */

package dotty.tools.io

import java.net.URL
import java.io.{ IOException, InputStream, OutputStream, FilterInputStream }
import java.nio.file.Files
import java.util.zip.{ ZipEntry, ZipFile }
import java.util.jar.Manifest
import scala.collection.mutable
import scala.collection.JavaConverters._

/** An abstraction for zip files and streams.  Everything is written the way
 *  it is for performance: we come through here a lot on every run.  Be careful
 *  about changing it.
 *
 *  @author  Philippe Altherr (original version)
 *  @author  Paul Phillips (this one)
 *  @version 2.0,
 *
 *  ''Note:  This library is considered experimental and should not be used unless you know what you are doing.''
 */
object ZipArchive {
  private[io] val closeZipFile: Boolean = sys.props.get("scala.classpath.closeZip").exists(_.toBoolean)

  /**
   * @param   file  a File
   * @return  A ZipArchive if `file` is a readable zip file, otherwise null.
   */
  def fromFile(file: File): FileZipArchive = fromPath(file.jpath)
  def fromPath(jpath: JPath): FileZipArchive = new FileZipArchive(jpath)

  def fromManifestURL(url: URL): AbstractFile = new ManifestResources(url)

  private def dirName(path: String)  = splitPath(path, front = true)
  private def baseName(path: String) = splitPath(path, front = false)
  private def splitPath(path0: String, front: Boolean): String = {
    val isDir = path0.charAt(path0.length - 1) == '/'
    val path  = if (isDir) path0.substring(0, path0.length - 1) else path0
    val idx   = path.lastIndexOf('/')

    if (idx < 0)
      if (front) "/"
      else path
    else
      if (front) path.substring(0, idx + 1)
      else path.substring(idx + 1)
  }
}
import ZipArchive._
/** ''Note:  This library is considered experimental and should not be used unless you know what you are doing.'' */
abstract class ZipArchive(override val jpath: JPath) extends AbstractFile with Equals {
  self =>

  override def underlyingSource: Option[ZipArchive] = Some(this)
  def isDirectory: Boolean = true
  def lookupName(name: String, directory: Boolean): AbstractFile = unsupported()
  def lookupNameUnchecked(name: String, directory: Boolean): AbstractFile = unsupported()
  def create(): Unit = unsupported()
  def delete(): Unit = unsupported()
  def output: OutputStream    = unsupported()
  def container: AbstractFile = unsupported()
  def absolute: AbstractFile  = unsupported()

  /** ''Note:  This library is considered experimental and should not be used unless you know what you are doing.'' */
  sealed abstract class Entry(path: String) extends VirtualFile(baseName(path), path) {
    // have to keep this name for compat with sbt's compiler-interface
    def getArchive: ZipFile = null
    override def underlyingSource: Option[ZipArchive] = Some(self)
    override def toString: String = self.path + "(" + path + ")"
  }

  /** ''Note:  This library is considered experimental and should not be used unless you know what you are doing.'' */
  class DirEntry(path: String) extends Entry(path) {
    val entries: mutable.HashMap[String, Entry] = mutable.HashMap()

    override def isDirectory: Boolean = true
    override def iterator: Iterator[Entry] = entries.valuesIterator
    override def lookupName(name: String, directory: Boolean): Entry = {
      if (directory) entries.get(name + "/").orNull
      else entries.get(name).orNull
    }
  }

  private def ensureDir(dirs: mutable.Map[String, DirEntry], path: String, zipEntry: ZipEntry): DirEntry =
    //OPT inlined from getOrElseUpdate; saves ~50K closures on test run.
    // was:
    // dirs.getOrElseUpdate(path, {
    //   val parent = ensureDir(dirs, dirName(path), null)
    //   val dir    = new DirEntry(path)
    //   parent.entries(baseName(path)) = dir
    //   dir
    // })
    dirs get path match {
      case Some(v) => v
      case None =>
        val parent = ensureDir(dirs, dirName(path), null)
        val dir    = new DirEntry(path)
        parent.entries(baseName(path)) = dir
        dirs(path) = dir
        dir
    }

  protected def getDir(dirs: mutable.Map[String, DirEntry], entry: ZipEntry): DirEntry = {
    if (entry.isDirectory) ensureDir(dirs, entry.getName, entry)
    else ensureDir(dirs, dirName(entry.getName), null)
  }
}
/** ''Note:  This library is considered experimental and should not be used unless you know what you are doing.'' */
final class FileZipArchive(jpath: JPath) extends ZipArchive(jpath) {
  private[this] def openZipFile(): ZipFile = try {
    new ZipFile(file)
  } catch {
    case ioe: IOException => throw new IOException("Error accessing " + file.getPath, ioe)
  }

  private[this] class LazyEntry(
    name: String,
    time: Long,
    size: Int
  ) extends Entry(name) {
    override def lastModified: Long = time // could be stale
    override def input: InputStream = {
      val zipFile  = openZipFile()
      val entry    = zipFile.getEntry(name)
      val `delegate` = zipFile.getInputStream(entry)
      new FilterInputStream(`delegate`) {
        override def close(): Unit = { zipFile.close() }
      }
    }
    override def sizeOption: Option[Int] = Some(size) // could be stale
  }

  // keeps a file handle open to ZipFile, which forbids file mutation
  // on Windows, and leaks memory on all OS (typically by stopping
  // classloaders from being garbage collected). But is slightly
  // faster than LazyEntry.
  private[this] class LeakyEntry(
    zipFile: ZipFile,
    zipEntry: ZipEntry
  ) extends Entry(zipEntry.getName) {
    override def lastModified: Long = zipEntry.getTime
    override def input: InputStream = zipFile.getInputStream(zipEntry)
    override def sizeOption: Option[Int] = Some(zipEntry.getSize.toInt)
  }

  @volatile lazy val (root, allDirs): (DirEntry, collection.Map[String, DirEntry]) = {
    val root = new DirEntry("/")
    val dirs = mutable.HashMap[String, DirEntry]("/" -> root)
    val zipFile = openZipFile()
    val entries = zipFile.entries()

    try {
      while (entries.hasMoreElements) {
        val zipEntry = entries.nextElement
        val dir = getDir(dirs, zipEntry)
        if (!zipEntry.isDirectory) {
          val f =
            if (ZipArchive.closeZipFile)
              new LazyEntry(
                zipEntry.getName(),
                zipEntry.getTime(),
                zipEntry.getSize().toInt
              )
            else
              new LeakyEntry(zipFile, zipEntry)

          dir.entries(f.name) = f
        }
      }
    } finally {
      if (ZipArchive.closeZipFile) zipFile.close()
    }
    (root, dirs)
  }

  def iterator: Iterator[Entry] = root.iterator

  def name: String       = jpath.getFileName.toString
  def path: String       = jpath.toString
  def input: InputStream = Files.newInputStream(jpath)
  def lastModified: Long = Files.getLastModifiedTime(jpath).toMillis

  override def sizeOption: Option[Int] = Some(Files.size(jpath).toInt)
  override def canEqual(other: Any): Boolean = other.isInstanceOf[FileZipArchive]
  override def hashCode(): Int = jpath.hashCode
  override def equals(that: Any): Boolean = that match {
    case x: FileZipArchive => jpath.toAbsolutePath == x.jpath.toAbsolutePath
    case _                 => false
  }
}

final class ManifestResources(val url: URL) extends ZipArchive(null) {
  def iterator(): Iterator[AbstractFile] = {
    val root     = new DirEntry("/")
    val dirs     = mutable.HashMap[String, DirEntry]("/" -> root)
    val manifest = new Manifest(input)
    val iter     = manifest.getEntries().keySet().iterator().asScala.filter(_.endsWith(".class")).map(new ZipEntry(_))

    for (zipEntry <- iter) {
      val dir = getDir(dirs, zipEntry)
      if (!zipEntry.isDirectory) {
        val f = new Entry(zipEntry.getName) {
          override def lastModified = zipEntry.getTime()
          override def input        = resourceInputStream(path)
          override def sizeOption   = None
        }
        dir.entries(f.name) = f
      }
    }

    try root.iterator
    finally dirs.clear()
  }

  def name: String  = path
  def path: String = {
    val s = url.getPath
    val n = s.lastIndexOf('!')
    s.substring(0, n)
  }
  def input: InputStream = url.openStream()
  def lastModified: Long =
    try url.openConnection().getLastModified()
    catch { case _: IOException => 0 }

  override def canEqual(other: Any): Boolean = other.isInstanceOf[ManifestResources]
  override def hashCode(): Int = url.hashCode
  override def equals(that: Any): Boolean = that match {
    case x: ManifestResources => url == x.url
    case _                => false
  }

  private def resourceInputStream(path: String): InputStream = {
    new FilterInputStream(null) {
      override def read(): Int = {
        if(in == null) in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)
        if(in == null) throw new RuntimeException(path + " not found")
        super.read()
      }

      override def close(): Unit = {
        super.close()
        in = null
      }
    }
  }
}
