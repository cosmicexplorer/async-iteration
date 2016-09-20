class EndOfAsyncIterationException extends Error {}

class AsyncIterator {
  next() {
    throw new EndOfAsyncIterationException();
  }

  value() {
    throw new EndOfAsyncIterationException();
  }
}

class Intermediate extends AsyncIterator {
  constructor(next, value) {
    this.nextPromise = next;
    this.curValue = value;
  }

  next() {
    return this.nextPromise.map(v => {
      if (v == null) {
        return new End();
      } else {
        return new Intermediate(
          v.next(), v.value()
        );
      }
    });
  }

  value() {
    return this.curValue;
  }
}

class End extends AsyncIterator {}
