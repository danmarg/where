import XCTest
import os
@testable import Where

final class BGTaskCompletionCoordinatorTests: XCTestCase {
    final class FakeBGTask: CompletableBGTask, @unchecked Sendable {
        private let lock = OSAllocatedUnfairLock(initialState: [Bool]())
        var completions: [Bool] { lock.withLock { $0 } }
        func markTaskCompleted(success: Bool) {
            lock.withLock { $0.append(success) }
        }
    }

    func testSuccessfulWork_completesOnceAndReschedules() async throws {
        let fakeTask = FakeBGTask()
        let rescheduleCount = OSAllocatedUnfairLock(initialState: 0)
        let coordinator = BGTaskCompletionCoordinator(task: fakeTask) {
            rescheduleCount.withLock { $0 += 1 }
        }

        let workDone = XCTestExpectation(description: "work ran")
        coordinator.start {
            workDone.fulfill()
        }
        await fulfillment(of: [workDone], timeout: 1.0)
        // Give the coordinator's post-work continuation a chance to run.
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertEqual(fakeTask.completions, [true], "should complete successfully exactly once")
        XCTAssertEqual(rescheduleCount.withLock { $0 }, 1, "must reschedule the next task exactly once")
    }

    final class LoggingBGTask: CompletableBGTask, @unchecked Sendable {
        let log: OSAllocatedUnfairLock<[String]>
        init(log: OSAllocatedUnfairLock<[String]>) { self.log = log }
        func markTaskCompleted(success: Bool) { log.withLock { $0.append("complete") } }
    }

    /// The next task must be submitted before this one completes: the system may suspend the
    /// app as soon as it's marked complete.
    func testReschedulesBeforeCompleting() async throws {
        for expire in [false, true] {
            let log = OSAllocatedUnfairLock(initialState: [String]())
            let coordinator = BGTaskCompletionCoordinator(task: LoggingBGTask(log: log)) {
                log.withLock { $0.append("schedule") }
            }
            if expire {
                coordinator.expire()
            } else {
                let workDone = XCTestExpectation(description: "work ran")
                coordinator.start { workDone.fulfill() }
                await fulfillment(of: [workDone], timeout: 1.0)
                try await Task.sleep(for: .milliseconds(50))
            }
            XCTAssertEqual(log.withLock { $0 }, ["schedule", "complete"], "expire=\(expire)")
        }
    }

    func testExpirationDuringWork_cancelsAndCompletesUnsuccessfullyOnce() async throws {
        let fakeTask = FakeBGTask()
        let rescheduleCount = OSAllocatedUnfairLock(initialState: 0)
        let coordinator = BGTaskCompletionCoordinator(task: fakeTask) {
            rescheduleCount.withLock { $0 += 1 }
        }

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
        XCTAssertEqual(rescheduleCount.withLock { $0 }, 1, "must reschedule the next task exactly once, even on expiration")
    }

    func testExpirationAfterWorkAlreadySucceeded_doesNotDoubleComplete() async throws {
        let fakeTask = FakeBGTask()
        let rescheduleCount = OSAllocatedUnfairLock(initialState: 0)
        let coordinator = BGTaskCompletionCoordinator(task: fakeTask) {
            rescheduleCount.withLock { $0 += 1 }
        }

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
        XCTAssertEqual(rescheduleCount.withLock { $0 }, 1, "must not reschedule a second time either")
    }
}
