/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.sampler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.data.Percentage.withPercentage;

class RateLimitingSamplerSoakTest {

  /** This test will take a little over a second */
  @ParameterizedTest
  @ValueSource(ints = {1, 11, 101, 1001, 1_000_001})
  void retainsPerSampleRate(int rate) throws Exception {
    Sampler sampler = RateLimitingSampler.create(rate);

    // We want to make sure we fill up the entire second, so
    long startTick = System.nanoTime();
    long lastDecisecond = startTick + TimeUnit.MILLISECONDS.toNanos(990);
    // Because exacts don't work with Thread.sleep + operation overhead, set a deadline just under a second
    long deadline = startTick + TimeUnit.MILLISECONDS.toNanos(998);

    AtomicBoolean hitLastDecisecond = new AtomicBoolean();

    AtomicLong passed = new AtomicLong();
    Runnable sample = () -> {
      long tick = System.nanoTime();
      if (tick > deadline) return;
      if (tick >= lastDecisecond) hitLastDecisecond.set(true);

      if (sampler.isSampled(0L)) passed.incrementAndGet();
    };

    Runnable loopAndSample = () -> {
      do {
        if (rate > 10) {  // execute one tenth of our rate
          for (int j = 0; j < rate / 10; j++) sample.run();
        } else {// don't divide by 10!
          sample.run();
        }
        sleepUninterruptibly(9, TimeUnit.MILLISECONDS);
      } while (System.nanoTime() < deadline);
    };

    int threadCount = 10;
    ExecutorService service = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(service.submit(loopAndSample));
    }

    service.shutdown();
    service.awaitTermination(1, TimeUnit.SECONDS);

    assertThat(passed.get())
      .isCloseTo(rate, withPercentage(0.01)); // accomodates flakes in CI
    assumeThat(hitLastDecisecond.get())
      .withFailMessage("ran out of samples before the end of the second")
      .isTrue();
  }
}
