package async_iteration

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import Util._

trait AsyncIterable[+T] {
  def iterator: AsyncIterator[T]
}

trait AsyncIterator[+T] {
  def next: Future[Option[NextIterator[T]]]
}

case class NextIterator[+T](
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

  class IteratorWithPage[+O, +T, +C <: Traversable[T]](
    offset: Option[O],
    pager: O => Future[Result[O, T, C]],
    curColl: C
  ) extends AsyncIterator[T] {
    override def next: Future[Option[NextIterator[T]]] =
      curColl.splitHeadTail match {
        case Some((v, rest)) =>
          val nextIt = new IteratorWithPage(offset, pager, rest)
          Future(Some(NextIterator(v, nextIt)))
        case None => offset.map { pager(_).flatMap {
          case Result(off, coll) =>
            new IteratorWithPage(off, pager, coll).next
        }}.getOrElse(Future(None))
      }
  }
}
