package async_iteration

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import Util._

trait AsyncIterable[+T, +F] extends AsyncTraversableOnce[T, F] {
  def iterator: AsyncIterator[T]
}

trait AsyncIterator[+T, +F] extends AsyncTraversableOnce[T, F] {
  def next: F[Option[Next[T]]]
  // def map[S](f: T => S): AsyncIterator[S] = new MapIterator(this)(f)
  // def filter(f: T => Boolean): AsyncIterator[T] = new FilterIterator(this)(f)
  // def reduce[S](init: S)(f: (S, T) => S): Future[S]
}

case class Next[+T](
  cur: T,
  next: AsyncIterator[T]
)

trait AsyncTraversableOnce[+T, +F] {
  type Pred = T => Boolean
  type ATO = AsyncTraversableOnce[T, F]

  def take(n: Int): ATO
  def takeWhile(f: Pred): ATO
  def drop(n: Int): ATO
  def dropWhile(f: Pred): ATO
  def slice(from: Int, to: Int): ATO
  def find(f: Pred): F[Option[T]]
  def forall(f: Pred): F[Boolean]
  def exists(f: Pred): F[Boolean]
  def count(f: Pred): F[Int]
  def foreach(f: T => Unit): F[Unit]
  def map[S](f: T => S): AsyncTraversableOnce[S, F]
  def filter(f: Pred): ATO
  def ++(other: ATO): ATO
  def isEmpty: F[Boolean]
  def nonEmpty: F[Boolean]
  // TODO: add min/max/sum!
  def maxBy[S : Ordering](f: T => S): F[T]
  def minBy[S : Ordering](f: T => S): F[T]
  def zipWith[S, U](other: AsyncTraversableOnce[S, F])(
    f: (T, S) => U
  ): AsyncTraversableOnce[U, F]

  // TODO: add other folds!
  def foldLeft[S](init: S)(f: (S, T) => S): F[S]

  // utilities
  def enumerate: F[TraversableOnce[T]]
  def convertFuture[O](implicit conv: F => O): AsyncTraversableOnce[T, O]
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
