# riscv64 QEMU VT crash — 분석 결과 (2026-09-30)

## 결론

1. **원인 경로: JFR 실행 샘플링(`jdk.ExecutionSample` / `jdk.NativeMethodSample`).** 샘플링을 끄면 crash가 사라지고, 주기를 20ms→1ms로 올리면 crash가 거의 항상 난다(dose-response).
2. **QEMU user-mode 에뮬레이션 환경에서만 관찰된다.** 실제 riscv64 하드웨어(RISE, Scaleway EM-RV1)에서는 같은 stress(1ms 샘플링, 15회)에서 0건이다.
3. **JVM의 RVV 코드 경로는 원인이 아니다.** `-XX:-UseRVV`로 꺼도 16/16 crash. QEMU 버전(8.2.2 / 10.2.3)과 zfh/zvfh 노출도 원인이 아니다.
4. **원래 CI 실패와 같은 문제다.** QEMU 1ms 케이스에서 CI 서명(`libjvm.so+0x6406b8`, `frame::interpreter_frame_method() const+0xe`, SIGSEGV)이 그대로 재현됐다.
5. **우리 쪽에서 해결 가능하다(제품 코드 수정 없이).** VT 테스트는 JFR을 `VirtualThreadPinned` 카운트에만 쓰므로, 테스트가 여는 recording에서 실행 샘플링 이벤트를 끄면 QEMU에서도 테스트를 돌릴 수 있다(제안, 아직 미적용).

## 근거

순수 JDK 프로브(SQLite 없음, `riscv-jfr-test`의 `PureJavaVt`), 단일 carrier, JDK 25.0.4.1.

### QEMU (GitHub-hosted, `run-on-arch-action`, Ubuntu 26.04 guest) — [run 36682553881](https://github.com/Clickin/riscv-jfr-test/actions/runs/36682553881), [run 36688870547](https://github.com/Clickin/riscv-jfr-test/actions/runs/36688870547)

| 케이스 | 실행 | crash | JFR 시작 실패 | 기타 |
|---|---:|---:|---:|---:|
| JFR off | 12 | 0 | 0 | 0 |
| JFR on (기본 20ms) | 12 | 0 | 0 | 1 |
| JFR on, 샘플링 이벤트 off | 12 | 0 | 0 | 0 |
| JFR on, 샘플링 1ms | 12 + 16 | 11 + 15 = **26 / 28** | 0 | 2 |
| JFR on, 샘플링 1ms, `-XX:-UseRVV` | 16 | **16 / 16** | 0 | 0 |

(첫 run의 JFR on 기본 케이스 `other` 1건은 `Failure when starting JFR on_create_vm_3`로 VM 초기화 중 실패였다.)

### RISE native (`ubuntu-24.04-riscv`, `rv64imafdcsu`, 커널 5.10, JVM `UseRVV/UseZba/UseZbb/UseZbs=false`) — 같은 run 36682553881, 400 rounds, 케이스당 15회, 약 47 s/run

| 케이스 | 통과 | crash |
|---|---:|---:|
| JFR off | 15 | 0 |
| JFR on (20ms) | 15 | 0 |
| JFR on, 샘플링 off | 15 | 0 |
| JFR on, 샘플링 1ms | 15 | **0** |

1ms 케이스 QEMU 11/12 vs native 0/15: Fisher p≈9×10⁻⁷. run당 시간이 비슷해(약 40~47초) 샘플러 신호 노출량 차이로는 설명되지 않는다.

### 로컬 QEMU 10.2.3 / 8.2.2 (4코어, 순수 Java, 20ms 기본)

| 조건 | crash |
|---|---:|
| JFR on 기본 (QEMU 10.2.3 기본, zfh/zvfh 끔, QEMU 8.2.2 합산) | 15 / 31 |
| JFR off | 0 / 10 |
| JFR on, 샘플링 off | 0 / 10 |
| QEMU 8.2.2만 | 3 / 6 |

이전에 "8.2.2는 2/2 통과"로 보인 것은 표본이 작았기 때문이다.

### crash 형태
`ForkJoinPool-1-worker-1`(단일 carrier) 또는 main 스레드가 continuation thaw/freeze, interpreter frame 조회, 클래스 링크 등을 하다가 죽는다: `ThawBase::recurse_thaw_*`, `Thaw::thaw_slow`, `frame::interpreter_frame_method`, `InterpreterRuntime::*`, `fieldDescriptor::reinitialize`(internal error), SEGV_ACCERR 등. 이전 조사의 `libz.so+0x78e2` inflate crash도 같은 계열로 보인다(JFR on 조건).

### 실제 SQLite 테스트(`VtCarrierProgressTest`, 로컬 QEMU guest)
JFR on(기본): child 15개 중 5개 실패(VM crash 2, exit≠0 3). JFR 샘플링 off: 5개 모두 정상(표본이 작아 참고용, 로컬 VM 재시작으로 반복 중단).

## 한계 (숨기지 않음)

- native 하드웨어에 RVV·Zb*·Zfh가 없다. JVM의 RVV는 배제했지만, **게스트 시스템 라이브러리(libz, libc)가 RVV를 쓰는 경로**와 QEMU의 RVV/Zb* 에뮬레이션 자체는 native와 분리되지 않았다.
- RVV 1.0이 실제로 있는 하드웨어에서는 검증하지 못했다(RISE 라벨에는 없음). 그 하드웨어에서 재현되면 OpenJDK/커널 쪽 리포트가 될 수 있다.
- 표본은 케이스당 12~30이고 QEMU 결과는 GitHub-hosted 4 vCPU 한 종류다.
- 샘플링 off 조건의 "crash 없음"은 0/12 + 0/10 + 0/8 수준의 관찰이며 안전 증명이 아니다.

## 제안

- **QEMU 리포트 초안 준비**(게시는 별도 확인): "linux-user riscv64, JDK 25 + JFR 실행 샘플링(샘플러가 다른 스레드의 스택을 읽는 경로; 정확한 메커니즘은 미확인) 하에서 무작위 메모리/코드 손상". QEMU 8.2.2와 10.2.3 모두 재현, 재현 저장소 `Clickin/riscv-jfr-test`. JVM 없는 최소 재현(신호 + 멀티스레드 JIT 코드 패치)은 아직 없다.
- **sqlite-jdbc CI**: `VtCarrierProgressTest`의 JFR 옵션에 `jdk.ExecutionSample#enabled=false,jdk.NativeMethodSample#enabled=false` 추가(pinned 이벤트 카운트에는 영향 없음)하고 QEMU job에서의 `SKIP_TEST_MULTIARCH` skip을 제거해 CI에서 검증. aarch64 alpine의 빈 recording 문제가 같이 해결되는지도 확인 대상.
