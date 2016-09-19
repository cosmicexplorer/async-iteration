package async_iteration

import scala.concurrent.Future

// purely functional!
sealed trait AsyncIterator[+T] {
  // consider parameterizing the future type
  def next: Future[AsyncIterator[T]]
  def value: T
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

trait AsyncIterable[+T] {
  def iterator: AsyncIterator[T]
}

trait 
