package async_iteration

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import Util._

trait AsyncIterable[+T] {
  def iterator: AsyncIterator[T]
}

trait AsyncIterator[+T] {
  def next: Future[Option[Next[T]]]
  def map[S](f: T => S): AsyncIterator[S] = new MapIterator(this)(f)
  def filter(f: T => Boolean): AsyncIterator[T] = new FilterIterator(this)(f)
  def reduce[S](init: S)(f: (S, T) => S): Future[S]
}

// functional operations on async iterators
class MapIterator[S, +T](init: AsyncIterator[S])(
  f: S => T
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = init.next.map(_.map {
    case Next(cur, nxt) => Next(f(cur), new MapIterator(nxt)(f))
  })
}

class FilterIterator[+T](init: AsyncIterator[T])(
  f: T => Boolean
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = init.next.flatMap(_.map {
    case Next(cur, nxt) =>
      if (f(cur)) {
        Future(Some(Next(cur, new FilterIterator(nxt, f))))
      } else {
        new FilterIterator(nxt, f).next
      }
  })
}

case class Next[+T](
  cur: T,
  next: AsyncIterator[T]
)

object Paging {
  case class Result[+O, +T, +C <: Traversable[T]](
    offset: Option[O], coll: C
  )

  def apply[O, T, C <: Traversable[T]](
    initOffset: O, pager: O => Future[Result[O, T, C]]
  ): AsyncIterator[T] = new IteratorWithPage(
    Some(initOffset), pager, Seq()
  )

  // TODO: optimize map by just applying to curColl!
  class IteratorWithPage[+O, +T, +C <: Traversable[T]](
    offset: Option[O],
    pager: O => Future[Result[O, T, C]],
    curColl: C
  ) extends AsyncIterator[T] {
    override def next: Future[Option[Next[T]]] =
      curColl.splitHeadTail match {
        case Some((v, rest)) =>
          val nextIt = new IteratorWithPage(offset, pager, rest)
          Future(Some(Next(v, nextIt)))
        case None => offset.map { pager(_).flatMap {
          case Result(off, coll) =>
            new IteratorWithPage(off, pager, coll).next
        }}.getOrElse(Future(None))
      }
  }
}
