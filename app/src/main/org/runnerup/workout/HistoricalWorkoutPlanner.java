/*
 * Copyright (C) 2026
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.workout;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.runnerup.common.util.Constants;
import org.runnerup.common.util.Constants.DB;

public final class HistoricalWorkoutPlanner {
  static final int DEFAULT_LOOKBACK_DAYS = 42;
  private static final long DAY_SECONDS = 24L * 60L * 60L;
  private static final double DEFAULT_BASELINE_PACE = 360d / 1000d;
  private static final double MIN_BASELINE_PACE = 210d / 1000d;
  private static final double MAX_BASELINE_PACE = 540d / 1000d;
  private static final double MIN_TARGET_PACE_FACTOR = 0.84d;
  private static final double MAX_TARGET_PACE_FACTOR = 1.0d;

  public enum WorkoutStyle {
    THRESHOLD_REPEATS(0, 0.93d, 0.97d),
    NORWEGIAN_4X4(1, 0.90d, 0.94d),
    PYRAMID(2, 0.90d, 0.95d),
    DESCENDING_LADDER(3, 0.89d, 0.94d),
    MONA_FARTLEK(4, 0.91d, 0.96d);

    final int id;
    final double minFactor;
    final double maxFactor;

    WorkoutStyle(int id, double minFactor, double maxFactor) {
      this.id = id;
      this.minFactor = minFactor;
      this.maxFactor = maxFactor;
    }

    public static WorkoutStyle fromId(int id) {
      for (WorkoutStyle style : values()) {
        if (style.id == id) {
          return style;
        }
      }
      return THRESHOLD_REPEATS;
    }
  }

  public enum Level {
    EASY(0, 8 * 60L, 8 * 60L, 1.2d, 0.01d),
    MODERATE(1, 10 * 60L, 8 * 60L, 1d, 0d),
    AGGRESSIVE(2, 12 * 60L, 10 * 60L, 0.8d, -0.01d);

    final int id;
    final long warmupSeconds;
    final long cooldownSeconds;
    final double recoveryScale;
    final double paceOffset;

    Level(int id, long warmupSeconds, long cooldownSeconds, double recoveryScale, double paceOffset) {
      this.id = id;
      this.warmupSeconds = warmupSeconds;
      this.cooldownSeconds = cooldownSeconds;
      this.recoveryScale = recoveryScale;
      this.paceOffset = paceOffset;
    }

    public static Level fromId(int id) {
      for (Level level : values()) {
        if (level.id == id) {
          return level;
        }
      }
      return MODERATE;
    }
  }

  static final class RunSample {
    final double distanceMeters;
    final long durationSeconds;

    RunSample(double distanceMeters, long durationSeconds) {
      this.distanceMeters = distanceMeters;
      this.durationSeconds = durationSeconds;
    }

    double paceSecondsPerMeter() {
      return distanceMeters > 0d && durationSeconds > 0L ? durationSeconds / distanceMeters : Double.NaN;
    }
  }

  static final class IntervalBlock {
    final int repeatCount;
    final long intervalSeconds;
    final long recoverySeconds;
    final Range targetPace;

    IntervalBlock(int repeatCount, long intervalSeconds, long recoverySeconds, Range targetPace) {
      this.repeatCount = repeatCount;
      this.intervalSeconds = intervalSeconds;
      this.recoverySeconds = recoverySeconds;
      this.targetPace = targetPace;
    }
  }

  public static final class Plan {
    final int runCount;
    final double baselinePaceSecondsPerMeter;
    final List<IntervalBlock> intervalBlocks;
    final Workout workout;

    Plan(
        int runCount,
        double baselinePaceSecondsPerMeter,
        List<IntervalBlock> intervalBlocks,
        Workout workout) {
      this.runCount = runCount;
      this.baselinePaceSecondsPerMeter = baselinePaceSecondsPerMeter;
      this.intervalBlocks = intervalBlocks;
      this.workout = workout;
    }

    public double getBaselinePaceSecondsPerMeter() {
      return baselinePaceSecondsPerMeter;
    }

    public Workout getWorkout() {
      return workout;
    }
  }

  private static final class BlockSpec {
    final int repeatCount;
    final long intervalSeconds;
    final long recoverySeconds;

    BlockSpec(int repeatCount, long intervalSeconds, long recoverySeconds) {
      this.repeatCount = repeatCount;
      this.intervalSeconds = intervalSeconds;
      this.recoverySeconds = recoverySeconds;
    }
  }

  private HistoricalWorkoutPlanner() {}

  public static Workout generateWorkout(
      SQLiteDatabase database, int sport, WorkoutStyle style, Level level) {
    Plan plan = generate(database, sport, DEFAULT_LOOKBACK_DAYS, style, level);
    return plan == null ? null : plan.workout;
  }

  public static Plan generatePlan(
      SQLiteDatabase database, int sport, WorkoutStyle style, Level level) {
    return generate(database, sport, DEFAULT_LOOKBACK_DAYS, style, level);
  }

  static Plan generate(
      SQLiteDatabase database, int sport, int lookbackDays, WorkoutStyle style, Level level) {
    if (!Sport.valueOf(sport).IsRunning()) {
      return null;
    }

    int safeLookbackDays = Math.max(1, lookbackDays);
    long cutoffStartTimeSeconds = System.currentTimeMillis() / 1000L - safeLookbackDays * DAY_SECONDS;
    return generateForRuns(loadRuns(database, cutoffStartTimeSeconds), sport, style, level);
  }

  static Plan generateForRuns(List<RunSample> runs, int sport, WorkoutStyle style, Level level) {
    if (!Sport.valueOf(sport).IsRunning()) {
      return null;
    }

    ArrayList<Double> paces = new ArrayList<>();
    for (RunSample run : runs) {
      double pace = run.paceSecondsPerMeter();
      if (!Double.isNaN(pace) && !Double.isInfinite(pace)) {
        paces.add(pace);
      }
    }

    Collections.sort(paces);
    double baselinePace = clampBaselinePace(median(paces));
    List<IntervalBlock> blocks = buildBlocks(style, level, baselinePace);
    return new Plan(
        runs.size(),
        baselinePace,
        blocks,
        buildWorkout(sport, level.warmupSeconds, level.cooldownSeconds, blocks));
  }

  private static ArrayList<RunSample> loadRuns(SQLiteDatabase database, long cutoffStartTimeSeconds) {
    ArrayList<RunSample> runs = new ArrayList<>();
    Cursor cursor =
        database.query(
            DB.ACTIVITY.TABLE,
            new String[] {DB.ACTIVITY.DISTANCE, DB.ACTIVITY.TIME},
            DB.ACTIVITY.DELETED + " = 0 AND " + DB.ACTIVITY.START_TIME + " >= ? AND "
                + DB.ACTIVITY.SPORT + " IN (?, ?, ?)",
            new String[] {
              Long.toString(cutoffStartTimeSeconds),
              Integer.toString(DB.ACTIVITY.SPORT_RUNNING),
              Integer.toString(DB.ACTIVITY.SPORT_ORIENTEERING),
              Integer.toString(DB.ACTIVITY.SPORT_TREADMILL)
            },
            null,
            null,
            DB.ACTIVITY.START_TIME + " DESC");
    try {
      while (cursor.moveToNext()) {
        double distanceMeters = cursor.getDouble(0);
        long durationSeconds = cursor.getLong(1);
        if (distanceMeters > 0d && durationSeconds > 0L) {
          runs.add(new RunSample(distanceMeters, durationSeconds));
        }
      }
    } finally {
      cursor.close();
    }
    return runs;
  }

  private static List<IntervalBlock> buildBlocks(
      WorkoutStyle style, Level level, double baselinePace) {
    BlockSpec[] specs = getBlockSpecs(style, level);
    Range targetPace = buildTargetPace(style, level, baselinePace);
    ArrayList<IntervalBlock> blocks = new ArrayList<>(specs.length);
    for (BlockSpec spec : specs) {
      blocks.add(
          new IntervalBlock(
              spec.repeatCount,
              spec.intervalSeconds,
              scaleRecovery(spec.recoverySeconds, level.recoveryScale),
              targetPace));
    }
    return blocks;
  }

  private static BlockSpec[] getBlockSpecs(WorkoutStyle style, Level level) {
    return switch (style) {
      case THRESHOLD_REPEATS -> thresholdRepeats(level);
      case NORWEGIAN_4X4 -> norwegian4x4(level);
      case PYRAMID -> pyramid(level);
      case DESCENDING_LADDER -> descendingLadder(level);
      case MONA_FARTLEK -> monaFartlek(level);
    };
  }

  private static BlockSpec[] thresholdRepeats(Level level) {
    return switch (level) {
      case EASY -> new BlockSpec[] {block(4, 3 * 60L, 75L)};
      case MODERATE -> new BlockSpec[] {block(5, 4 * 60L, 75L)};
      case AGGRESSIVE -> new BlockSpec[] {block(6, 5 * 60L, 60L)};
    };
  }

  private static BlockSpec[] norwegian4x4(Level level) {
    return switch (level) {
      case EASY -> new BlockSpec[] {block(4, 3 * 60L, 180L)};
      case MODERATE -> new BlockSpec[] {block(4, 4 * 60L, 180L)};
      case AGGRESSIVE -> new BlockSpec[] {block(5, 4 * 60L, 150L)};
    };
  }

  private static BlockSpec[] pyramid(Level level) {
    return switch (level) {
      case EASY -> new BlockSpec[] {
        block(1, 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 3 * 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 60L, 60L)
      };
      case MODERATE -> new BlockSpec[] {
        block(1, 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 3 * 60L, 60L),
        block(1, 4 * 60L, 60L),
        block(1, 3 * 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 60L, 60L)
      };
      case AGGRESSIVE -> new BlockSpec[] {
        block(1, 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 3 * 60L, 60L),
        block(1, 4 * 60L, 60L),
        block(1, 5 * 60L, 60L),
        block(1, 4 * 60L, 60L),
        block(1, 3 * 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 60L, 60L)
      };
    };
  }

  private static BlockSpec[] descendingLadder(Level level) {
    return switch (level) {
      case EASY -> new BlockSpec[] {
        block(1, 4 * 60L, 90L),
        block(1, 3 * 60L, 90L),
        block(1, 2 * 60L, 90L),
        block(1, 60L, 90L)
      };
      case MODERATE -> new BlockSpec[] {
        block(1, 5 * 60L, 75L),
        block(1, 4 * 60L, 75L),
        block(1, 3 * 60L, 75L),
        block(1, 2 * 60L, 75L),
        block(1, 60L, 75L)
      };
      case AGGRESSIVE -> new BlockSpec[] {
        block(1, 6 * 60L, 60L),
        block(1, 5 * 60L, 60L),
        block(1, 4 * 60L, 60L),
        block(1, 3 * 60L, 60L),
        block(1, 2 * 60L, 60L),
        block(1, 60L, 60L)
      };
    };
  }

  private static BlockSpec[] monaFartlek(Level level) {
    return switch (level) {
      case EASY -> new BlockSpec[] {
        block(4, 30L, 30L),
        block(4, 30L, 30L),
        block(4, 15L, 15L)
      };
      case MODERATE -> new BlockSpec[] {
        block(2, 90L, 90L),
        block(4, 60L, 60L),
        block(4, 30L, 30L),
        block(4, 15L, 15L)
      };
      case AGGRESSIVE -> new BlockSpec[] {
        block(2, 120L, 120L),
        block(2, 90L, 90L),
        block(4, 60L, 60L),
        block(4, 30L, 30L),
        block(4, 15L, 15L)
      };
    };
  }

  private static BlockSpec block(int repeatCount, long intervalSeconds, long recoverySeconds) {
    return new BlockSpec(repeatCount, intervalSeconds, recoverySeconds);
  }

  private static Range buildTargetPace(WorkoutStyle style, Level level, double baselinePace) {
    return new Range(
        baselinePace * clampFactor(style.minFactor + level.paceOffset),
        baselinePace * clampFactor(style.maxFactor + level.paceOffset));
  }

  private static Workout buildWorkout(
      int sport, long warmupSeconds, long cooldownSeconds, List<IntervalBlock> blocks) {
    Workout workout = new Workout();
    workout.sport = sport;
    workout.setWorkoutType(Constants.WORKOUT_TYPE.ADVANCED);
    workout.getSteps().add(buildSimpleStep(Intensity.WARMUP, warmupSeconds, null));
    for (IntervalBlock block : blocks) {
      if (block.repeatCount > 1) {
        RepeatStep repeatStep = new RepeatStep();
        repeatStep.setRepeatCount(block.repeatCount);
        repeatStep.getSteps().add(buildSimpleStep(Intensity.ACTIVE, block.intervalSeconds, block.targetPace));
        repeatStep.getSteps().add(Step.createRestStep(Dimension.TIME, block.recoverySeconds, true));
        workout.getSteps().add(repeatStep);
      } else {
        workout.getSteps().add(buildSimpleStep(Intensity.ACTIVE, block.intervalSeconds, block.targetPace));
        workout.getSteps().add(Step.createRestStep(Dimension.TIME, block.recoverySeconds, true));
      }
    }
    workout.getSteps().add(buildSimpleStep(Intensity.COOLDOWN, cooldownSeconds, null));
    return workout;
  }

  private static Step buildSimpleStep(Intensity intensity, long seconds, Range targetPace) {
    Step step = new Step();
    step.setIntensity(intensity);
    step.setDurationType(Dimension.TIME);
    step.setDurationValue(seconds);
    if (targetPace != null) {
      step.setTargetType(Dimension.PACE);
      step.setTargetValue(targetPace.minValue, targetPace.maxValue);
    }
    return step;
  }

  private static double median(List<Double> sortedValues) {
    if (sortedValues.isEmpty()) {
      return DEFAULT_BASELINE_PACE;
    }
    int middle = sortedValues.size() / 2;
    return (sortedValues.size() & 1) == 1
        ? sortedValues.get(middle)
        : (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2d;
  }

  private static double clampBaselinePace(double paceSecondsPerMeter) {
    return Math.max(MIN_BASELINE_PACE, Math.min(MAX_BASELINE_PACE, paceSecondsPerMeter));
  }

  private static double clampFactor(double factor) {
    return Math.max(MIN_TARGET_PACE_FACTOR, Math.min(MAX_TARGET_PACE_FACTOR, factor));
  }

  private static long scaleRecovery(long recoverySeconds, double recoveryScale) {
    return Math.max(15L, Math.round((recoverySeconds * recoveryScale) / 15d) * 15L);
  }
}
