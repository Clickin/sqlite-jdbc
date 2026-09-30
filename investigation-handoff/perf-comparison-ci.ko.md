# xerial 3.53.4.0 vs fork (`vt-java-wait`) — CI 기반 성능 비교

작성: 2026-09-30. 기존 GitHub Actions 결과를 artifact 원본에서 다시 집계했다. 새 벤치마크는 실행하지 않았다.

## 1. VT CI 안정성 (비교의 전제)

| 항목 | 상태 |
|---|---|
| HEAD `c6a16c9` 기존 CI | [run 36647070110](https://github.com/Clickin/sqlite-jdbc/actions/runs/36647070110) — 32개 job 중 31 success, `Deploy`만 skipped |
| 직전 run 7 (`98c6153`) | 실패 1건: Windows GraalVM `ControlTransactionTest` t01이 1초 대기 예산 안에 blocker 해제를 못 봄 → `c6a16c9`에서 10초로 수정 |
| run 5 (`06bc3b9`) | 실패: `SqliteJdbcFeature`가 옛 JNI 이름을 등록 → native-image 18개 job 전부 실패. 수정됨 |
| `VtCarrierProgressTest` | x86_64/aarch64 native runner, macOS, Windows에서 실행됨. QEMU job(armv7·aarch64 alpine·ppc64le·riscv64)에서는 `SKIP_TEST_MULTIARCH`로 건너뜀 |

판단: 수정 후 green이 확인된 것은 **1회**다. 실패 원인이 모두 타이밍 예산·등록 누락이라 수정은 타당하지만, "안정화"를 주장하려면 같은 HEAD에서 재실행이 더 필요하다. QEMU에서의 VT 테스트 커버리지는 없다(riscv64 원인 분석은 별도 진행 중).

## 2. 비교 방법의 공통 통제

`vt-lock-comparison.yml`, `vt-benchmark.yml` 모두 한 job 안에서 순차 실행하며 다음을 고정한다.

- xerial `cab7981` (3.53.4.0)을 같은 JDK(Temurin 25.0.2)·같은 javac·GCC 13·`release 8`로 새로 빌드
- **SQLite 오브젝트(`sqlite3.o`)는 한 번만 컴파일**해 xerial·fork JNI에 동일하게 링크 (sha256 `52c0c3e3…`)
- 구현 순서 회전, 새 JVM마다 새 DB, 결과 검증(작업 수·무결성) 실패 시 해당 셀 무효

한계: GitHub-hosted 4 vCPU VM이며 run마다 CPU 세대가 다를 수 있어 **서로 다른 run의 절대값은 비교하지 않는다.** 비율은 같은 run 안에서만 의미가 있다.

## 3. 결과 (원본 artifact에서 재집계)

### 3-1. 고정 작업량 JDBC 미세 벤치 — [run 36584178780](https://github.com/Clickin/sqlite-jdbc/actions/runs/36584178780) (fork `4198d50`)

`UPDATE counters SET n=n+1 WHERE id=?` 5,000,000회, 4회 반복. 4198d50 이후 HEAD까지 `src/main`의 JVM 런타임 변경은 `SqliteJdbcFeature`(GraalVM 등록)뿐이라 HEAD의 JVM 성능을 대표한다.

| 시나리오 | xerial ops/s (중앙값) | fork ops/s (중앙값) | fork/xerial | CPU ns/op xerial → fork | 잡음 표시 |
|---|---:|---:|---:|---:|---|
| single (worker 1, 연결 1) | 1,017,094 | 1,013,457 | **0.996** | 988 → 991 | CV 1.7% / 0.4% |
| private16 (16 VT, 연결 16) | 2,493,846 | 2,475,482 | **0.993** | 1,593 → 1,600 | CV 0.7% / 0.9% |
| shared4 (4 VT, 연결 1) | 666,647 | 702,892 | **1.054** | 2,984 → 2,837 | xerial CV 5.2% (noisy) |
| shared16 (16 VT, 연결 1) | 717,537 | 707,738 | **0.986** | 2,803 → 2,832 | fork CV 16.4% (noisy) |

해석: 비경합 경로는 xerial과 사실상 동등(−0.4% ~ −0.7%). shared 경합 셀은 분산이 커서(CV 5–19%) 우열을 말할 수 없다. 이 벤치가 스스로 "descriptive이며 승자를 정하지 않는다"고 표기한다.

### 3-2. Gateway HTTP/MCP 앱 벤치 — [run 36573132854](https://github.com/Clickin/sqlite-jdbc/actions/runs/36573132854) (fork `4adeef4`)

12 client, carrier 4, 30초, 조건별 6회. `before`는 parity 작업 이전 fork 리비전.

| 부하 | 드라이버 | 성공 req/s 중앙값 (min–max) | 요청 실패(6회 합) | HTTP p99 ms | 독립 VT 하트비트 p99 ms |
|---|---|---:|---:|---:|---:|
| HTTP만 | xerial | 174.78 (173.8–175.9) | 0 | 122.2 | 0.8 |
| HTTP만 | before | 166.29 (165.7–168.9) | 0 | 128.5 | 0.8 |
| HTTP만 | fork | 172.54 (169.8–174.0) | 0 | 123.6 | 0.8 |
| 백업 4개 동시 | xerial | 76.05 (74.8–77.6) | **253** | 1,315 | 1,185 |
| 백업 4개 동시 | before | 125.64 (124.2–127.2) | 0 | 675 | 519 |
| 백업 4개 동시 | fork | 129.27 (127.7–130.5) | 0 | 673 | 551 |

- 정상 부하: fork/xerial = **0.987 (−1.3%)**, CPU/성공 요청 11.49 → 11.70 ms (+1.8%).
- 백업 경합: fork/xerial = **1.700 (+70%)**, 독립 VT 하트비트 p99 −53%, 요청 실패 253 → 0.
- xerial 실패는 모두 동시성 한도(`HTTP_CONCURRENCY_LIMIT`) 계열이며, 이 워크플로는 실패를 숨기지 않고 CI failure로 끝나도록 설계돼 있다(그래서 run 결론이 failure).

### 3-3. 과거 Gateway run과의 추이 (문서 기재값, 이번에 재집계하지 않음)

| run | 대상 | 정상 부하 fork/xerial | 백업 경합 처리량 |
|---|---|---:|---:|
| 36304158851 (`fefbb74`, 9/27) | v3 성능 커밋 이전 | 0.952 (−4.79%) | +65.5% |
| 36573132854 (`4adeef4`, 9/29) | 모니터 inflation 수정 후 | 0.987 (−1.3%) | +70% |

같은 run 안의 비율이라 추이 비교는 유효하지만, run마다 runner CPU가 달라 절대값(req/s)은 비교하지 않는다.

## 4. 결론

1. **경합 없는 경로**: xerial과 동등(−1% 안팎). 처음의 −4.8% 격차는 v3 커밋(monitor inflation 제거 등)으로 대부분 줄었다.
2. **백업 등 긴 busy 대기가 있는 경로**: 큰 이점(+65~70% 처리량, xerial 요청 실패 제거, 독립 VT 지연 절반 이하). 이 개선은 carrier 진행성이며 pin 이벤트 수 감소가 아니다(양쪽 pin=0).
3. **공유 연결 경합(shared4/16)**: 통계적으로 구분 불가(노이즈 큼).

## 5. 이 비교의 빈틈 (숨기지 않고 남김)

- **Gateway 앱 벤치는 `b2b0f92`(fast path) 이전 코드**(`4adeef4`)다. 최신 HEAD 기준 Gateway run은 없다. lock 미세 벤치만 fast path 이후(`4198d50`)다.
- `4adeef4`(EPYC 7763)와 `4198d50`(EPYC 9V45)의 lock 비교는(CPU 모델은 기존 문서 기재값이며 이번 artifact의 `environment.json`에는 없다) runner CPU가 달라 fast path 효과를 분리할 수 없다(shared4: 0.568 → 1.054).
- 주요 셀 다수가 noisy 표시이고 표본은 4~6회다. 신뢰구간이 아니라 기술 통계다.
- 모두 x86_64 GitHub-hosted 결과다. riscv64·aarch64 등에서의 성능 비교는 없다.

## 6. 다음 단계 제안

HEAD(`c6a16c9`)를 기준으로 `benchmarks/vt/<날짜>`와 `benchmarks/vt-lock/<날짜>` 브랜치를 push해 두 워크플로를 한 번씩 돌리면, 최신 코드에 대해 같은 run 안 비교가 완성된다(각 약 1시간, Actions 무료 범위). 새 브랜치 push라 확인 후 진행한다.
