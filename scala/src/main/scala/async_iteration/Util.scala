package async_iteration

object Util {
  implicit class TraversalOps[S](
    coll: Traversable[S]
  ) {
    def splitHeadTail: Option[(S, Traversable[S])] = {
      val (firstSeq, rest) = coll.splitAt(1)
      firstSeq.toSeq match {
        case Seq(v) => Some((v, rest))
        case Seq() => None
      }
    }
  }
}
