# Gateway 과부하·admission 관측

## 목적 변경

이 실험은 **Gateway의 고정된 admission 정책 아래 완료율·거절률·지연·VT carrier 가용성을 관측**한다. JDBC 락 자체의 비용을 비교하는 실험이 아니다. 이전 보고서의 CPU/성공 요청이나 전체 Gateway 처리량을 근거로 락 구현을 추천한 해석은 철회한다. 락 비교는 [별도 고정 작업량 JDBC 실험](CI-LOCK-COMPARISON.md)으로 분리했다.

`HTTP_CONCURRENCY_LIMIT`은 소스 HTTP 커넥터가 semaphore permit을 `min(queueTimeoutMs, remaining)` 안에 얻지 못했을 때 발생한다. SQLite 오류가 아니며 클라이언트 HTTP 429를 뜻하는 것도 아니다. 실제 응답은 HTTP 200인 MCP 오류 envelope 안에 해당 code를 담는다. 거절은 실행되지 못한 애플리케이션 요청으로 보존하되, 그 자체를 JDBC correctness 실패로 취급하지 않는다.

## 관측 조건

공개 Gateway fixture의 source HTTP 설정은 pool total/per-route **4/4**, maxConcurrent **4**, queue timeout **1,000ms**, connect timeout **2,000ms**, operation timeout **5,000ms**다. 부하는 12개 client, VT carrier 4개다. 백업 경합에서는 별도 platform thread가 destination을 2초마다 750ms 잠그고 4개 backup을 실행한다.

이 조건에서 측정하는 것은 보편적인 최대 용량이 아니라 **해당 정책·부하에서의 서비스 완료와 거절 양상**이다. 거절 발생 원인을 permit 수 부족만으로 단정하지 않는다. carrier 지연, permit 반환 지연 등은 별도 원인 분석 대상이다. Zero JFR pin event도 JNI 내부 carrier 점유가 없다는 증거는 아니다.

## 현재 판정 계약

`run-gateway-overload.py`만 `-Dbenchmark.overload=true`를 지정한다. 기존 `run-gateway.py`와 `run-cost-profile.py`의 strict 기본 동작은 유지한다.

- `attempts = successes + admission_rejections + other_failures`를 검증한다. 모든 응답을 완료한 뒤 집계하며 재시도하거나 거절을 성공으로 바꾸지 않는다.
- `HTTP_CONCURRENCY_LIMIT`은 HTTP 상태, MCP `isError`, structured outcome과 exact error code가 모두 맞을 때만 admission 거절로 분류한다. 이름만 포함한 다른 오류를 흡수하지 않는다.
- **experiment_valid:** 전체 관측 완료, 정확한 source/native identity, 일치하는 accounting, source-call 수, backup 완료 수, DB integrity, recording 검증, unexpected error 없음. Admission 거절 수는 유효성 실패 조건이 아니다.
- **application_all_requests_success:** 실제 요청 실패/거절이 전혀 없었는지 별도 표시한다. 유효한 과부하 관측도 이 값은 false일 수 있다. 임의의 SLO 합격선을 만들지 않는다.
- 다른 HTTP/JDBC/backup 오류, 누락된 결과, 불일치한 집계는 계속 nonzero exit다.
- 완료율/거절률, attempt rate, success rate, 전체 attempt latency, heartbeat 지연, timer jitter, 총 CPU와 CPU/attempt를 보고한다. CPU에는 backup·거절·실패 처리도 포함되며 순수 JDBC 또는 성공 요청 한 건의 비용이 아니다.
- Warmup도 admission 거절을 별도로 센다. 원래 측정 `result.json`과 error 기록을 보존하고 runner 판정은 `observation.json`에 따로 남긴다.

## 기존 CI 관측의 재분류

아래는 이미 종료된 두 GitHub CI의 **백업 경합 구간**이다. 각 구현의 네 repetition을 합친 완료/거절 수와 비율이며 heartbeat는 기존 JVM별 p99의 중앙값이다. 모든 당시 요청 실패는 `HTTP_CONCURRENCY_LIMIT`이었다.

| 실행 | 구현 | 완료 요청 | admission 거절 | 완료율 | 거절률 | heartbeat p99 중앙값 ms |
|---|---|---:|---:|---:|---:|---:|
| 36451753558 | xerial | 7,863 | 192 | 97.62% | 2.38% | 1238.49 |
| 36451753558 | fork | 13,191 | 0 | 100.00% | 0.00% | 570.43 |
| 36451753558 | flat | 13,334 | 0 | 100.00% | 0.00% | 544.12 |
| 36451753558 | reentrant | 13,302 | 0 | 100.00% | 0.00% | 573.28 |
| 36457676492 | xerial | 2,234 | 93 | 96.00% | 4.00% | 1859.18 |
| 36457676492 | fork | 6,078 | 5 | 99.92% | 0.08% | 1163.88 |
| 36457676492 | flat | 7,305 | 2 | 99.97% | 0.03% | 1192.92 |
| 36457676492 | reentrant | 6,185 | 4 | 99.94% | 0.06% | 1246.93 |

- [첫 실행 36451753558](https://github.com/Clickin/sqlite-jdbc/actions/runs/36451753558): source `07bd0dabe97d8cb291f383519e029af6aca9093d`. flat은 conn monitor를 DB monitor로 **교체**한 후보다.
- [두 번째 36457676492](https://github.com/Clickin/sqlite-jdbc/actions/runs/36457676492): source `011127b1a4e035624ffd6256e27f8eb83001e1b6`. flat은 이미 보호된 내부 monitor를 **제거**한 후보다.
- 두 실행 모두 32개 JVM 측정과 integrity 검사를 완료했다. Backup은 각 구현/실행에서 224건 모두 성공했다. 모든 정상 부하 요청은 성공했다.
- 당시 workflow는 strict collector를 사용했으므로 `failure`다. **새 관측 목적에 맞춰 과거 GitHub conclusion이나 raw outcome_passed 값을 소급 변경하지 않았다.**
- 두 번째 실행의 높은 처리량 변동과 두 job의 다른 환경을 그대로 보존한다. 이를 락 성능 승패나 오류율의 일반적 보장으로 해석하지 않는다.

원래 64개 run의 수치, 코드/native hash, callback, artifact identity는 [역사적 결과 JSON](ci-gateway-overload-results.json)에 보존했다. 상단 목적/해석만 정정했고 `runs` 배열은 변경하지 않았다. 원본 JFR/CSV와 로그는 각 GitHub artifact에 남으며 보존 기간은 30일이다.

## 실행

- [Gateway overload workflow](../../.github/workflows/vt-gateway-overload.yml): `benchmarks/vt-overload/**` 또는 workflow_dispatch.
- [관측 runner](../../scripts/benchmark/run-gateway-overload.py), [실제 Gateway workload](../../scripts/benchmark/GatewayBenchmark.java).
- 락 workflow와 artifact 이름·결과 디렉터리를 분리한다. 새 artifact는 `ci-gateway-overload-*`, 결과는 `target/vt-wait-evidence/ci-gateway-overload`다.

Pinned JDK/GCC와 upstream을 준비한 새 checkout에서:

```sh
bash scripts/benchmark/build-ci.sh
python3 scripts/benchmark/build-lock-variants.py
python3 scripts/benchmark/run-gateway-overload.py --seconds 30 --warmup-requests 6000
```

별도 SLO나 부하 수준을 변경할 때는 동일 source admission 정책을 명시한 독립 실험으로 기록해야 한다. 과부하 결과를 admission이 없는 JDBC 고정 작업량 실험의 CPU/operation 표에 섞지 않는다.
