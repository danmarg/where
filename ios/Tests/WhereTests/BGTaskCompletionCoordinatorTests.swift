import XCTest
@testable import Where

final class BGTaskCompletionCoordinatorTests: XCTestCase {
    final class FakeBGTask: CompletableBGTask {
        private(set) var completions: [Bool] = []
        func markTaskCompleted(success: Bool) {
            completions.append(success)
        }
    }

    func testSuccessfulWork_completesOnceAndReschedules() async throws {
        let fakeTask = FakeBGTask()
        var rescheduleCount = 0
        let coordinator = BGTaskCompletionCoordinator(task: fakeTask) { rescheduleCount += 1 }

        let workDone = XCTestExpectation(description: "work ran")
        coordinator.start {
            workDone.fulfill()
        }
        await fulfillment(of: [workDone], timeout: 1.0)
        // Give the coordinator's post-work continuation a chance to run.
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertEqual(fakeTask.completions, [true], "should complete successfully exactly once")
        XCTAssertEqual(rescheduleCount, 1, "must reschedule the next task exactly once")
    }

    func testExpirationDuringWork_cancelsAndCompletesUnsuccessfullyOnce() async throws {
        let fakeTask = FakeBGTask()
        var rescheduleCount = 0
        let coordinator = BGTaskCompletionCoordinator(task: fakeTask) { rescheduleCount += 1 }

        let workStarted = XCTestExpectation(description: "work started")
        let workObservedCancellation = XCTestExpectation(description: "work observed cancellation")
        coordinator.start {
            workStarted.fulfill()
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(10))
            }
            workObservedCancellation.fulfill()
        }
        await fulfillment(of: [workStarted], timeout: 1.0)

        coordinator.expire()
        await fulfillment(of: [workObservedCancellation], timeout: 1.0)
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertEqual(fakeTask.completions, [false], "expiration must complete the task unsuccessfully exactly once")
        // Regression: this is the exact bug found in review — the original code only
        // rescheduled on the success path, so an expired task never resubmitted its
        // successor and background heartbeat polling stopped permanently.
        XCTAssertEqual(rescheduleCount, 1, "must reschedule the next task exactly once, even on expiration")
    }

    func testExpirationAfterWorkAlreadySucceeded_doesNotDoubleComplete() async throws {
        let fakeTask = FakeBGTask()
        var rescheduleCount = 0
        let coordinator = BGTaskCompletionCoordinator(task: fakeTask) { rescheduleCount += 1 }

        let workDone = XCTestExpectation(description: "work ran")
        coordinator.start {
            workDone.fulfill()
        }
        await fulfillment(of: [workDone], timeout: 1.0)
        try await Task.sleep(for: .milliseconds(50)) // let the success completion land first

        // A late expirationHandler call racing in after success already completed the task
        // must be a no-op, not a second setTaskCompleted call (a documented logic error).
        coordinator.expire()

        XCTAssertEqual(fakeTask.completions, [true], "success must win the race; expire() after success must not double-complete")
        XCTAssertEqual(rescheduleCount, 1, "must not reschedule a second time either")
    }
}
