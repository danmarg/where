"""Fire the app's BGTaskScheduler heartbeat launch handler from lldb and report whether it traps.

Run inside an lldb attached to the running app (see simulate_bgtask_heartbeat in
e2e/ios-upgrade-test.sh):

    lldb --batch -p <pid> -o "command script import e2e/lldb_simulate_bgtask.py"

This is Apple's documented way to exercise a background task (`_simulateLaunchForTaskWithIdentifier:`
from the debugger). The system calls the launch handler on a background queue, which is what the real
system does and what exposed the 1.31 crash: a handler that Swift 6 had inferred as @MainActor trapped
in _dispatch_assert_queue_fail on entry. Nothing else in CI runs that closure.

Prints exactly one `BGTASK_RESULT:` line, which the caller greps for:
  ok            the handler ran (or at least nothing trapped) for the whole settle window
  crashed ...   the process stopped on an exception/signal; the backtrace follows
  error ...     could not drive the app (expression failed, process exited, ...)
Also prints `BGTASK_HANDLER_OBSERVED: yes|no` (whether BGTaskScheduler's dispatch was seen running).
"""

import time

import lldb

TASK_ID = "net.af0.where.heartbeat"
SETTLE_SECONDS = 20  # the handler hops to a background queue and starts a poll; give it time
RUN_SELECTOR = "-[BGTaskScheduler _runTask:registration:]"


def _wait_for_state(process, wanted, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.GetState() in wanted:
            return True
        time.sleep(0.2)
    return process.GetState() in wanted


SIGSTOP = 17  # Darwin; lldb's own attach/stop signal, never a crash


def _fatal_stop(process):
    """(is_fatal, description) for the process's current stop.

    Only an exception (EXC_BREAKPOINT from a Swift runtime trap, EXC_BAD_ACCESS, ...) or a signal
    other than SIGSTOP counts. A stop with no reason is lldb's own bookkeeping (e.g. just after
    resuming), not the app dying.
    """
    fatal = False
    lines = []
    for thread in process:
        reason = thread.GetStopReason()
        if reason == lldb.eStopReasonException:
            fatal = True
        elif reason == lldb.eStopReasonSignal:
            if thread.GetStopReasonDataAtIndex(0) == SIGSTOP:
                continue
            fatal = True
        else:
            continue
        lines.append("thread %d stop reason: %s" % (thread.GetIndexID(), thread.GetStopDescription(256)))
        for frame in thread.frames[:25]:
            lines.append("    %s" % frame)
    return fatal, "\n".join(lines)


def _simulate_launch(debugger, process):
    """Triggers the simulated launch; returns [] on success, else one error line per attempt.

    The first attempt that works wins. The first form is the one Apple documents; the others avoid
    depending on the BackgroundTasks headers being visible to lldb's expression parser (a private
    selector on a class lldb only knows from the ObjC runtime), and print lldb's own error text.
    """
    thread = process.GetThreadAtIndex(0)
    process.SetSelectedThread(thread)
    frame = thread.GetFrameAtIndex(0)

    options = lldb.SBExpressionOptions()
    options.SetLanguage(lldb.eLanguageTypeObjC)
    options.SetUnwindOnError(True)
    options.SetTryAllThreads(True)
    options.SetTimeoutInMicroSeconds(15 * 1000 * 1000)

    direct = '(void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"%s"]' % TASK_ID
    by_name = (
        '(void)[(id)[(id)NSClassFromString(@"BGTaskScheduler") performSelector:'
        'NSSelectorFromString(@"sharedScheduler")] performSelector:'
        'NSSelectorFromString(@"_simulateLaunchForTaskWithIdentifier:") withObject:@"%s"]' % TASK_ID
    )

    errors = []
    result = frame.EvaluateExpression(direct, options)
    if result.GetError().Success():
        print("BGTASK_TRIGGER: frame.EvaluateExpression(direct)")
        return []
    errors.append("frame.EvaluateExpression(direct): %s" % result.GetError().GetCString())

    interp = debugger.GetCommandInterpreter()
    for label, expr in (("command direct", direct), ("command by-name", by_name)):
        res = lldb.SBCommandReturnObject()
        interp.HandleCommand("expression -l objc -- " + expr, res)
        if res.Succeeded():
            print("BGTASK_TRIGGER: %s" % label)
            return []
        errors.append("%s: %s" % (label, (res.GetError() or res.GetOutput() or "").strip()))
    return errors


def __lldb_init_module(debugger, _internal_dict):
    target = debugger.GetSelectedTarget()
    process = target.GetProcess()
    if not process.IsValid():
        print("BGTASK_RESULT: error not attached to a process")
        return

    # Count how often the system's dispatch of the handler runs, then carry on. Soft evidence that
    # the simulated launch really reached the handler, so a no-op doesn't pass silently.
    bp = target.BreakpointCreateByName(RUN_SELECTOR)
    bp.SetAutoContinue(True)

    errors = _simulate_launch(debugger, process)
    if errors:
        print("BGTASK_RESULT: error could not trigger the simulated launch:")
        print("\n".join(errors))
        process.Detach()
        return

    debugger.SetAsync(True)
    process.Continue()
    # Async Continue returns before the state flips; reading "stopped" right away is not a crash.
    _wait_for_state(process, {lldb.eStateRunning, lldb.eStateExited, lldb.eStateCrashed}, 10)

    deadline = time.time() + SETTLE_SECONDS
    while time.time() < deadline:
        state = process.GetState()
        if state == lldb.eStateStopped:
            fatal, description = _fatal_stop(process)
            if fatal:
                print("BGTASK_RESULT: crashed")
                print(description)
                process.Detach()
                return
            process.Continue()  # a benign stop (no reason, or lldb's SIGSTOP): keep going
            _wait_for_state(process, {lldb.eStateRunning, lldb.eStateExited, lldb.eStateCrashed}, 10)
        elif state in (lldb.eStateExited, lldb.eStateCrashed, lldb.eStateDetached):
            print("BGTASK_RESULT: crashed process %s" % lldb.SBDebugger.StateAsCString(state))
            return
        time.sleep(0.5)

    process.Stop()
    _wait_for_state(process, {lldb.eStateStopped}, 10)
    print("BGTASK_HANDLER_OBSERVED: %s" % ("yes" if bp.GetHitCount() > 0 else "no"))
    print("BGTASK_RESULT: ok")
    process.Detach()
