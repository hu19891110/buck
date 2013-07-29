/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.rules;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEventAtTime;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.InstallEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class SimpleConsoleEventBusListenerTest {
  @Test
  public void testSimpleBuild() {
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1));
    Supplier<Long> threadIdSupplier = BuckEventBus.getDefaultThreadIdSupplier();
    EventBus rawEventBus = new EventBus();
    BuckEventBus eventBus = new BuckEventBus(rawEventBus, fakeClock, threadIdSupplier);
    TestConsole console = new TestConsole();

    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//banana:stand");
    ImmutableList<BuildTarget> buildTargets = ImmutableList.of(fakeTarget);
    FakeBuildRule fakeRule = new FakeBuildRule(BuildRuleType.GENRULE,
        fakeTarget,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    SimpleConsoleEventBusListener listener = new SimpleConsoleEventBusListener(console, fakeClock);
    eventBus.register(listener);

    final long threadId = 0;

    rawEventBus.post(configureTestEventAtTime(BuildEvent.started(buildTargets),
        0L, TimeUnit.MILLISECONDS, threadId));
    rawEventBus.post(configureTestEventAtTime(ParseEvent.started(buildTargets),
        0L, TimeUnit.MILLISECONDS, threadId));

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(ParseEvent.finished(buildTargets),
        400L, TimeUnit.MILLISECONDS, threadId));

    final String parsingLine = "[-] PARSING BUILD FILES...FINISHED 0.40s\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(BuildRuleEvent.started(fakeRule),
        600L, TimeUnit.MILLISECONDS, threadId));

    rawEventBus.post(configureTestEventAtTime(BuildRuleEvent.finished(
        fakeRule,
        BuildRuleStatus.SUCCESS,
        CacheResult.MISS,
        Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)),
        1000L, TimeUnit.MILLISECONDS, threadId));
    rawEventBus.post(configureTestEventAtTime(BuildEvent.finished(buildTargets, 0),
        1234L, TimeUnit.MILLISECONDS, threadId));

    final String buildingLine = "[-] BUILDING...FINISHED 0.83s\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(LogEvent.severe("I've made a huge mistake."),
        1500L, TimeUnit.MILLISECONDS, threadId));

    final String logLine = "I've made a huge mistake.\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine + logLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(InstallEvent.started(fakeTarget),
        2500L, TimeUnit.MILLISECONDS, threadId));

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine + logLine,
        console.getTextWrittenToStdErr());

    rawEventBus.post(configureTestEventAtTime(InstallEvent.finished(fakeTarget, true),
        4000L, TimeUnit.MILLISECONDS, threadId));

    final String installLine = "[-] INSTALLING...FINISHED 1.50s\n";

    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals(parsingLine + buildingLine + logLine + installLine,
        console.getTextWrittenToStdErr());
  }

}
