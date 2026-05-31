package com.pooli.traffic.service.batch;

enum LineDailyUsageSyncWorkerRunResult {
    /** 이번 cycle에서 row를 처리했으므로 다음 worker cycle을 즉시 이어서 실행한다. */
    CONTINUE_IMMEDIATELY,

    /** 선점 가능한 row는 없지만 non-terminal row가 남아 있어 1분 뒤 다시 확인한다. */
    WAIT_FOR_EMPTY_POLL,

    /** 더 진행할 worker cycle이 없으므로 scheduler 재예약을 중단한다. */
    STOP
}
