package io.chrisdavenport.fuuid

import cats._
import cats.implicits._
import cats.effect.Sync
import java.util.UUID
import java.security.MessageDigest

import scala.reflect.macros.blackbox

final class FUUID private (private val uuid: UUID){

  // Direct show method so people do not use toString
  def show: String = uuid.show
  // -1 less than, 0 equal to, 1 greater than
  def compare(that: FUUID): Int = this.uuid.compareTo(that.uuid)

  // Returns 0 when equal
  def eqv(that: FUUID): Boolean = compare(that) == 0

  override def equals(obj: scala.Any): Boolean = obj match {
    case that: FUUID => eqv(that)
    case _ => false
  }
  override def hashCode: Int = uuid.hashCode
  override def toString: String = uuid.toString

}

object FUUID {
  implicit val instancesFUUID: Hash[FUUID] with Order[FUUID] with Show[FUUID] =
    new Hash[FUUID] with Order[FUUID] with Show[FUUID]{
      override def show(t: FUUID): String = t.show
      override def eqv(x: FUUID, y: FUUID): Boolean = x.eqv(y)
      override def hash(x: FUUID): Int = x.hashCode
      override def compare(x: FUUID, y: FUUID): Int = x.compare(y)
    }

  def fromString(s: String): Either[Throwable, FUUID] =
    Either.catchNonFatal(new FUUID(UUID.fromString(s)))

  def fromStringOpt(s: String): Option[FUUID] = 
    fromString(s).toOption

  def fromStringF[F[_]](s: String)(implicit AE: ApplicativeError[F, Throwable]): F[FUUID] =
    fromString(s).fold(AE.raiseError, AE.pure)

  def fromUUID(uuid: UUID): FUUID = new FUUID(uuid)

  def randomFUUID[F[_]: Sync]: F[FUUID] = Sync[F].delay(
    new FUUID(UUID.randomUUID)
  )

  def fuuid(s: String): FUUID = macro Macros.fuuidLiteral

  private[FUUID] class Macros(val c: blackbox.Context) {
    import c.universe._
    def fuuidLiteral(s: c.Expr[String]): c.Expr[FUUID] =
      s.tree match {
        case Literal(Constant(s: String))=>
            fromString(s)
            .fold(
              e => c.abort(c.enclosingPosition, e.getMessage.replace("UUID", "FUUID")),
              _ => c.Expr(q"""
                @SuppressWarnings(Array("org.wartremover.warts.Throw"))
                val fuuid = _root_.io.chrisdavenport.fuuid.FUUID.fromString($s).fold(throw _, _root_.scala.Predef.identity)
                fuuid
              """)
            )
        case _ =>
          c.abort(
            c.enclosingPosition,
            s"This method uses a macro to verify that a FUUID literal is valid. Use FUUID.fromString if you have a dynamic value you want to parse as an FUUID."
          )
      }
  }

  /**
    * Creates a new name-based UUIDv5
    **/ 
  def nameBased[F[_]](namespace: FUUID, name: String)(implicit AE: ApplicativeError[F, Throwable]): F[FUUID] =
    Either
      .catchNonFatal(
        MessageDigest
          .getInstance("SHA-1")
          .digest(
            uuidBytes(namespace) ++
              name.getBytes("UTF-8")
          )
      )
      .map { bs =>
        val cs = bs.take(16) // Truncate 160 bits (20 bytes) SHA-1 to fit into our 128 bits of space
        cs(6) = (cs(6) & 0x0f).asInstanceOf[Byte] /* clear version                */
        cs(6) = (cs(6) | 0x50).asInstanceOf[Byte] /* set to version 5, SHA1 UUID  */
        cs(8) = (cs(8) & 0x3f).asInstanceOf[Byte] /* clear variant                */
        cs(8) = (cs(8) | 0x80).asInstanceOf[Byte] /* set to IETF variant          */
        cs
      }
      .flatMap(
        bs =>
          Either.catchNonFatal {
            val bb = java.nio.ByteBuffer.allocate(java.lang.Long.BYTES)
            bb.put(bs, 0, 8)
            bb.flip
            val most = bb.getLong
            bb.flip
            bb.put(bs, 8, 8)
            bb.flip
            val least = bb.getLong
            new FUUID(new UUID(most, least))
          }
      )
      .liftTo[F]

  private def uuidBytes(fuuid: FUUID): Array[Byte] = {
    val bb = java.nio.ByteBuffer.allocate(2 * java.lang.Long.BYTES)
    bb.putLong(fuuid.uuid.getMostSignificantBits)
    bb.putLong(fuuid.uuid.getLeastSignificantBits)
    bb.array
  }

  /**
    * A Home For functions we don't trust
    * Hopefully making it very clear that this code needs
    * to be dealt with carefully.
    *
    * Likely necessary for some interop
    *
    * Please do not import directly but prefer `FUUID.Unsafe.xxx`
    **/
  object Unsafe {
    def toUUID(fuuid: FUUID): UUID = fuuid.uuid
    def withUUID[A](fuuid: FUUID)(f: UUID => A): A = f(fuuid.uuid)
  }

}
