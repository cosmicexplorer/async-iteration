package async_iteration

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class IterationState[+T](
  curCollection: Iterable[T],
  nextRequest: () => Future[Iterable[T]]
)

// purely functional!
sealed trait AsyncIterator[+T] {
  // consider parameterizing the future type
  def next: Future[AsyncIterator[T]]
}

class EndOfAsyncIterationException extends Exception

case class Intermediate[+T](
  next: Future[AsyncIterator[T]],
  value: T
) extends AsyncIterator[T]
object End extends AsyncIterator[Nothing] {
  // TODO: just make this throw? or return future?
  override def next: Future[AsyncIterator[Nothing]] =
    Future.failed(new EndOfAsyncIterationException)
  override def value: Nothing = throw new EndOfAsyncIterationException
}

object AsyncIterator {
  def fromSync[T](it: Iterator[T]): AsyncIterator[T] = {
    val next = it.next
    if (it.hasNext) Intermediate(Future(fromSync(it)), next) else End
  }
}

trait AsyncIterable[+T] {
  def iterator: AsyncIterator[T]
}
