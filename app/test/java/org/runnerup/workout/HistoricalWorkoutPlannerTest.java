package org.runnerup.workout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.runnerup.common.util.Constants.DB;

public class HistoricalWorkoutPlannerTest {

  @Test
  public void shouldRejectNonRunningSport() {
    HistoricalWorkoutPlanner.Plan plan =
        HistoricalWorkoutPlanner.generateForRuns(
            List.of(),
            DB.ACTIVITY.SPORT_BIKING,
            HistoricalWorkoutPlanner.WorkoutStyle.THRESHOLD_REPEATS,
            HistoricalWorkoutPlanner.Level.MODERATE);

    assertNull(plan);
  }

  @Test
  public void shouldGenerateWorkoutEvenWithoutHistory() {
    HistoricalWorkoutPlanner.Plan plan =
        HistoricalWorkoutPlanner.generateForRuns(
            List.of(),
            DB.ACTIVITY.SPORT_RUNNING,
            HistoricalWorkoutPlanner.WorkoutStyle.THRESHOLD_REPEATS,
            HistoricalWorkoutPlanner.Level.MODERATE);

    assertNotNull(plan);
    assertEquals(0, plan.runCount);
    assertTrue(plan.workout.getSteps().size() >= 3);
    assertTrue(plan.baselinePaceSecondsPerMeter > 0d);
  }

  @Test
  public void shouldGenerateThresholdRepeatsWorkout() {
    HistoricalWorkoutPlanner.Plan plan =
        HistoricalWorkoutPlanner.generateForRuns(
            mediumLoadRuns(),
            DB.ACTIVITY.SPORT_RUNNING,
            HistoricalWorkoutPlanner.WorkoutStyle.THRESHOLD_REPEATS,
            HistoricalWorkoutPlanner.Level.MODERATE);

    assertNotNull(plan);
    assertEquals(1, plan.intervalBlocks.size());
    assertEquals(5, plan.intervalBlocks.get(0).repeatCount);
    assertEquals(4 * 60L, plan.intervalBlocks.get(0).intervalSeconds);
    assertEquals(3, plan.workout.getSteps().size());
  }

  @Test
  public void shouldGeneratePyramidBlocks() {
    HistoricalWorkoutPlanner.Plan plan =
        HistoricalWorkoutPlanner.generateForRuns(
            mediumLoadRuns(),
            DB.ACTIVITY.SPORT_RUNNING,
            HistoricalWorkoutPlanner.WorkoutStyle.PYRAMID,
            HistoricalWorkoutPlanner.Level.MODERATE);

    assertNotNull(plan);
    assertEquals(7, plan.intervalBlocks.size());
    assertEquals(60L, plan.intervalBlocks.get(0).intervalSeconds);
    assertEquals(4 * 60L, plan.intervalBlocks.get(3).intervalSeconds);
  }

  @Test
  public void shouldMakeAggressiveLevelHarderThanEasy() {
    HistoricalWorkoutPlanner.Plan easyPlan =
        HistoricalWorkoutPlanner.generateForRuns(
            mediumLoadRuns(),
            DB.ACTIVITY.SPORT_RUNNING,
            HistoricalWorkoutPlanner.WorkoutStyle.DESCENDING_LADDER,
            HistoricalWorkoutPlanner.Level.EASY);
    HistoricalWorkoutPlanner.Plan aggressivePlan =
        HistoricalWorkoutPlanner.generateForRuns(
            mediumLoadRuns(),
            DB.ACTIVITY.SPORT_RUNNING,
            HistoricalWorkoutPlanner.WorkoutStyle.DESCENDING_LADDER,
            HistoricalWorkoutPlanner.Level.AGGRESSIVE);

    assertNotNull(easyPlan);
    assertNotNull(aggressivePlan);
    assertTrue(totalWorkSeconds(aggressivePlan) > totalWorkSeconds(easyPlan));
    assertTrue(
        aggressivePlan.intervalBlocks.get(0).targetPace.minValue
            < easyPlan.intervalBlocks.get(0).targetPace.minValue);
    assertTrue(
        aggressivePlan.intervalBlocks.get(0).recoverySeconds
            <= easyPlan.intervalBlocks.get(0).recoverySeconds);
  }

  private static List<HistoricalWorkoutPlanner.RunSample> mediumLoadRuns() {
    return List.of(
        run(7000, 41 * 60),
        run(8000, 46 * 60),
        run(7500, 43 * 60),
        run(9000, 52 * 60),
        run(6000, 35 * 60));
  }

  private static HistoricalWorkoutPlanner.RunSample run(double distance, long duration) {
    return new HistoricalWorkoutPlanner.RunSample(distance, duration);
  }

  private static long totalWorkSeconds(HistoricalWorkoutPlanner.Plan plan) {
    long total = 0L;
    for (HistoricalWorkoutPlanner.IntervalBlock block : plan.intervalBlocks) {
      total += block.repeatCount * block.intervalSeconds;
    }
    return total;
  }
}
