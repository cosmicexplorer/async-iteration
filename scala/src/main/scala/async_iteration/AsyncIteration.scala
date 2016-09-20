package async_iteration

import scala.collection.{GenTraversable, GenTraversableOnce}
import scala.concurrent.Future
import scala.concurrent.Future.Implicits.global
import Util._

case class Next[+T](
  cur: T,
  next: AsyncIterator[T]
)

trait AsyncIterator[+T] {
  // must be implemented
  def next: Future[Option[Next[T]]]

  type Pred = T => Boolean
  type ATO = AsyncIterator[T]

  def take(n: Int): ATO = new TakeWrapper(this, n, 0)
  def takeWhile(f: Pred): ATO = new TakeWhileWrapper(this, f)
  def drop(n: Int): ATO = new DropWrapper(this, n, 0)
  def dropWhile(f: Pred): ATO = new DropWhileWrapper(this, f)
  def slice(from: Int, to: Int): ATO = {
    assert(from <= to)
    drop(from).take(to - from)
  }
  def size: Future[Int] = foldLeft(0) { (cur, _) => cur + 1 }
  def find(f: Pred): Future[Option[T]] = dropWhile(!f(_)).next.map(_.map(_.cur))
  def forall(f: Pred): Future[Boolean] = dropWhile(!f(_)).next.map(_.isEmpty)
  def exists(f: Pred): Future[Boolean] = find.map(_.nonEmpty)
  def count(f: Pred): Future[Int] = filter(f).size
  def foreach(f: T => Unit): Future[Unit] = next.flatMap {
    case None => Future.Unit
    case Some(Next(cur, nxt)) =>
      f(cur)
      nxt.next
  }
  def map[S](f: T => S): AsyncIterator[S] = new MapWrapper(this, f)
  def filter(f: Pred): ATO = new FilterWrapper(this, f)
  def ++(other: ATO): ATO = new ConcatWrapper(this, other)
  def isEmpty: Future[Boolean] = next.map(_.isEmpty)
  def nonEmpty: Future[Boolean] = next.map(_.nonEmpty)

  // TODO: max(By), min(By)
  def max[B >: T](implicit cmp: Ordering[B]): Future[Option[T]]
  def min[B >: T](implicit cmp: Ordering[B]): Future[T]
  def maxBy[S : Ordering](f: T => S): Future[T]
  def minBy[S : Ordering](f: T => S): Future[T]

  def sum[B >: T](implicit num: Numeric[B]): Future[T] =
    foldLeft(num.zero)(num.plus)

  def zip[S](other: AsyncIterator[S]): AsyncIterator[(T, S)] =
    zipWith(other) { (first, second) => (first, second) }
  def zipWith[S, U](other: AsyncIterator[S])(
    f: (T, S) => U
  ): AsyncIterator[U] = new ZipWrapper(this, other, f)

  // folds
  def foldLeft[S](init: S)(f: (S, T) => S): Future[S] = {
    var result = init
    foreach(t => result = f(result, t))
    result
  }

  // flattening
  def flatten[S](
    implicit conv: T => GenTraversable[S]
  ): AsyncIterator[S] = new SyncWrapper(this)
  def flatten[S](
    implicit conv: T => AsyncIterator[S]
  ): AsyncIterator[S] = next.flatMap {
    case None => Future.None
    case Some(Next(cur, nxt)) => new FlattenWrapper(cur, nxt).next
  }
  def flatten[S](
    implicit conv: T => Future[S]
  ): AsyncIterator[S] = new FutureWrapper(this)

  def flatMap[S, U](f: T => S)(
    implicit conv: S => GenTraversable[U]
  ): AsyncIterator[U] = map(f).flatten
  def flatMap[S, U](f: T => S)(
    implicit conv: S => AsyncIterator[U]
  ): AsyncIterator[U] = map(f).flatten
  def flatMap[S, U](f: T => S)(
    implicit conv: S => Future[U]
  ): AsyncIterator[U] = map(f).flatten

  // utilities
  // TODO: better return type than traversableonce!
  def enumerate: Future[Seq[T]] = enumerateAccumulate(Seq())
  private def enumerateAccumulate(
    start: Seq[T]
  ): Future[Seq[T]] =
    next.flatMap {
      case None => Future(start)
      case Some(Next(cur, nxt)) => nxt.enumerateAccumulate(start :+ cur)
    }
}

class TakeWrapper[+T](
  init: AsyncIterator[T], limit: Int, cur: Int
) extends AsyncIterator[T] {
  assert(cur <= limit)
  override def next: Future[Option[Next[T]]] =
    if (cur == limit) {
      Future.None
    } else {
      init.next.map(_.map {
        case Next(cur, nxt) =>
          Next(cur, new TakeWrapper(nxt, limit, cur + 1))
      })
    }
}

class TakeWhileWrapper[+T](
  init: AsyncIterator[T], f: T => Boolean
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] =
    init.next.map(_.filter(f(_.cur)).map {
      case Next(cur, nxt) => Next(cur, new TakeWhileWrapper(nxt, f))
    })
}

class DropWrapper[+T](
  init: AsyncIterator[T], limit: Int, cur: Int
) extends AsyncIterator[T] {
  assert(cur <= limit)
  override def next: Future[Option[Next[T]]] =
    if (cur == limit) {
      init.next
    } else {
      init.next.flatMap {
        case None => Future.None
        case Some(Next(_, nxt)) => new DropWrapper(nxt, limit, cur + 1).next
      }
    }
}

class DropWhileWrapper[+T](
  init: AsyncIterator[T], f: T => Boolean
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] =
    init.next.flatMap {
      case None => Future.None
      case Some(Next(cur, nxt)) =>
        if (f(cur)) {
          new DropWhileWrapper(nxt, f).next
        } else {
          Future(Some(Next(cur, nxt)))
        }
    }
}

class MapWrapper[+S, +T](init: AsyncIterator[S])(
  f: S => T
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = init.next.map(_.map {
    case Next(cur, nxt) => Next(f(cur), new MapIterator(nxt)(f))
  })
}

class FilterWrapper[+T](init: AsyncIterator[T])(
  f: T => Boolean
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = init.next.flatMap(_.map {
    case Next(cur, nxt) =>
      val nextIt = new FilterIterator(nxt, f)
      if (f(cur)) {
        Future(Some(Next(cur, nextIt)))
      } else {
        nextIt.next
      }
  })
}

class ConcatWrapper[+T](
  first: AsyncIterator[T], second: AsyncIterator[T]
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = init.next.flatMap { opt =>
    opt.map(Future(Some(_))).getOrElse(second.next)
  }
}

class ZipWrapper[+T, +S, +U](
  first: AsyncIterator[T], second: AsyncIterator[S], f: (T, S) => U
) extends AsyncIterator[U] {
  override def next: Future[Option[Next[T]]] =
    first.next.zip(second.next).map {
      case (tOpt, sOpt) => tOpt.zip(sOpt).map {
        case (t, s) => Next(f(t.cur, s.cur), new ZipWrapper(t.next, s.next, f))
      }
    }
}

class EmptyIterator[+T] extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = Future(None)
}

class SingleValueWrapper[+T] (
  value: T
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] =
    Future(Some(Next(value, new EmptyIterator[T])))
}

// TODO: if iterator wraps up a bunch of synchronous iterables, perform faster
// operations on them when possible
class SyncWrapper[+T, +S <: GenTraversable[T]](
  base: AsyncIterator[S], curColl: S = Seq()
) extends AsyncIterator[T] {
  def this(coll: S) {
    this(new EmptyIterator[T], coll)
  }

  // avoid using this when possible!
  override def next: Future[Option[Next[T]]] =
    curColl.splitHeadTail match {
      case Some((head, tail)) =>
        Future(Some(Next(head, new SyncWrapper(base, tail))))
      case None => base.next.flatMap(_.map {
        case Next(cur, rest) => new SyncWrapper(rest, cur).next
      }.getOrElse(Future.None))
    }
}

class FlattenWrapper[+T](
  base: AsyncIterator[T], rest: AsyncIterator[AsyncIterator[T]]
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] = {
    base.next.flatMap(_.map(Future(Some(_))).orElse {
      rest.next.flatMap(_.map {
        case None => Future.None
        case Some(Next(it, restOfIts)) => new FlattenWrapper(it, restOfIts).next
      })
    })
  }
}

class FutureWrapper[+T](
  base: AsyncIterator[Future[T]]
) extends AsyncIterator[T] {
  override def next: Future[Option[Next[T]]] =
    base.next.flatMap {
      case None => Future.None
      case Some(Next(cur, nxt)) =>
        val it = new FutureWrapper(nxt)
        cur.map(t => Some(Next(t, it)))
    }
}

// object Paging {
//   case class Result[+O, +T, +C <: Traversable[T]](
//     offset: Option[O], coll: C
//   )

//   def apply[O, T, C <: Traversable[T]](
//     initOffset: O, pager: O => Future[Result[O, T, C]]
//   ): AsyncIterator[T] = new IteratorWithPage(
//     Some(initOffset), pager, Seq()
//   )

//   // TODO: optimize map by just applying to curColl!
//   class IteratorWithPage[+O, +T, +C <: Traversable[T]](
//     offset: Option[O],
//     pager: O => Future[Result[O, T, C]],
//     curColl: C
//   ) extends AsyncIterator[T] {
//     override def next: Future[Option[Next[T]]] =
//       curColl.splitHeadTail match {
//         case Some((v, rest)) =>
//           val nextIt = new IteratorWithPage(offset, pager, rest)
//           Future(Some(Next(v, nextIt)))
//         case None => offset.map { pager(_).flatMap {
//           case Result(off, coll) =>
//             new IteratorWithPage(off, pager, coll).next
//         }}.getOrElse(Future(None))
//       }
//   }
// }
