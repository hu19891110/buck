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

import com.facebook.buck.cli.InstallEvent;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of {@code AbstractConsoleEventBusListener} for terminals that don't support ansi.
 */
public class SimpleConsoleEventBusListener extends AbstractConsoleEventBusListener {
  private final AtomicLong parseTime;

  public SimpleConsoleEventBusListener(Console console, Clock clock) {
    super(console, clock);

    this.parseTime = new AtomicLong(0);
  }

  @Override
  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    super.parseFinished(finished);
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    this.parseTime.set(logEventPair("PARSING BUILD FILES",
        clock.currentTimeMillis(),
        0L,
        parseStarted,
        parseFinished,
        lines));
    printLines(lines);
  }

  @Override
  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    super.buildFinished(finished);
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    logEventPair("BUILDING",
        clock.currentTimeMillis(),
        parseTime.get(),
        buildStarted,
        buildFinished,
        lines);
    printLines(lines);
  }

  @Override
  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    super.installFinished(finished);
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    logEventPair("INSTALLING",
        clock.currentTimeMillis(),
        0L,
        installStarted,
        installFinished,
        lines);
    printLines(lines);
  }

  @Subscribe
  public void logEvent(LogEvent event) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    formatLogEvent(event, lines);
    printLines(lines);
  }

  private void printLines(ImmutableList.Builder<String> lines) {
    // Print through the {@code DirtyPrintStreamDecorator} so printing from the simple console
    // is considered to dirty stderr and stdout and so it gets synchronized to avoid interlacing
    // output.
    console.getStdErr().println(Joiner.on("\n").join(lines.build()));
  }
}
