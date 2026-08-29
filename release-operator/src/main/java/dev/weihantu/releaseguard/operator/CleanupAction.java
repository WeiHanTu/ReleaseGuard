package dev.weihantu.releaseguard.operator;

enum CleanupAction {
  RequestAbort,
  WaitForStableTraffic,
  DeleteChildren,
  OrphanStable,
  Complete
}
