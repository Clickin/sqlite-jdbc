# 실제 애플리케이션 VT 벤치마크

**결론:** JDK 25에서 carrier 4개가 native 백업 대기로 포화될 때 fork의 독립 VT p99 지연은 **815 → 96ms(88.2% 감소)**였다. 그러나 정상 HTTP 처리량은 **6~11% 낮았고**, carrier 10개에서는 백업 4개를 추가해도 처리량 이득이 없었다. 측정된 개선은 native 대기로 인한 carrier 고갈 완화이지 SQLite 전체의 nonblocking 보장이나 JFR pin event 감소율이 아니다.

## 범위와 이전 이력

이번 비교 대상은 xerial `org.xerial:sqlite-jdbc:3.53.4.0`과 이 작업 트리의 `3.53.4.1-SNAPSHOT`이다. fork는 `9415fee6` 이후 코드 리뷰 수정까지 포함한다. 드라이버 성능 최적화나 애플리케이션 production 코드 수정은 하지 않았다.

이전 `../sqlite3_vfs/IMPLEMENTATION-RESULTS.md`의 GeoPackage Java 6.6.7 비교는 JDBC/ORMLite 호환성 조사였다. 네트워크 타일 다운로드, 무작위 fixture, 기존 JPEG 실패가 포함되어 전체 실행 시간을 처리량으로 비교할 수 없었고, VT 사용도 입증하지 않았다. 그 수치를 이번 VT 개선율의 기준으로 사용하지 않는다.

이번에는 이미 SQLite와 VT를 사용하는 실제 로컬 Gateway 애플리케이션을 실행했다.

- 소스: `../gateway`, HEAD `885c6b8b28a61c479986b55f405c17ac4fade6f9` + 기존 미커밋 애플리케이션 변경. 이 로컬 저장소에는 `origin` remote가 없다. 깨끗한 HEAD만으로 재현되는 실험이라고 주장하지 않으며, 실행 당시 status는 `environment.json`에 보존한다.
- 경로: HTTP/MCP → Micronaut 5.1.5 → 인증·권한 검사·워크플로 실행 → MyBatis/HikariCP/Flyway → 파일 SQLite.
- `GatewayFixture`/`GatewayHttpClient`를 그대로 재사용한다. OAuth 로그인·PKCE·동의를 거쳐 토큰을 발급받고 `tools/call`로 환자 A/문서 DA의 합성 검사 결과를 요청한다.
- 외부 의료 시스템과 인증 authority는 기존 테스트용 합성 구현이다. 실제 운영 트래픽/운영 데이터 테스트라고 주장하지 않는다. HTTP 서버, JDBC, DB, 권한/워크플로 production 경로는 실제 실행한다.
- `/test/runtime` 응답의 `virtual=true`를 요구한다. 모든 요청은 HTTP 200, `SUCCEEDED`, 예상 결과 문자열을 확인한다. 성공 수와 합성 upstream 호출 수가 같아야 한다.
- production `PlatformStore` 설정 유지: WAL, synchronous FULL, reader pool 8, writer pool 1, autoCommit false, 기본 DEFERRED transaction. writer는 pool에서 직렬화하므로 정상 요청이 SQLite busy wait를 많이 일으킨다고 가정하지 않는다.

## 측정 방법

- macOS 26.5.2 / ARM64, Mac16,1, 논리 CPU 10개, 메모리 16 GiB, Temurin 25.0.2+10.
- 별도 JVM/새 DB로 매번 재시작. driver classpath entry만 교체한다. heap 512 MiB, Netty event-loop 4개, 동시 HTTP client 12개. 부하 발생기는 platform thread라 서버 VT carrier를 직접 소비하지 않는다.
- 실행당 인증/시작 비용을 제외하고 120 HTTP 요청을 warmup한 뒤 20초 동안 closed-loop 부하. 마지막 요청 drain까지 elapsed에 포함한다.
- 같은 scenario를 3회씩 실행하며 순서는 xerial→fork, fork→xerial, xerial→fork. 두 드라이버나 다른 측정 JVM을 동시에 실행하지 않는다.
- 주 비교는 VT scheduler parallelism/maxPoolSize 모두 4인 carrier 제한 조건이다. CPU affinity 또는 컨테이너 CPU quota가 4라는 뜻은 아니다.
- 시나리오: (1) HTTP만, (2) HTTP + 온라인 백업 1개, (3) HTTP + 온라인 백업 4개. 백업 부하는 기존 애플리케이션 기능이라고 주장하지 않으며, 동일한 실제 platform DB에 JDBC online-backup 유지보수 부하를 추가한 실험이다.
- 백업은 2초 간격, 시작 1초 후부터 총 9회 wave. 각 destination에 별도 platform-thread 제어 연결이 EXCLUSIVE lock을 750ms 유지한다. xerial/fork에 동일한 lock 일정과 파일 정책을 적용한다. 전체 페이지 스냅샷(`pagesPerStep=-1`), retry sleep 10ms, retry count 300. lock 해제는 VT에 의존하지 않는다.
- 독립 platform timer가 10ms마다 짧은 VT를 제출한다. 제출→실행 시작 지연의 p50/p95/p99/max와 20/100/500ms 초과 횟수를 수집한다. 이것은 **독립 VT의 start-delay proxy**이지 JVM 내부 scheduler queue의 직접 측정값은 아니다. timer 자체 지연도 별도 수집한다.
- JFR `jdk.VirtualThreadPinned` threshold 0, stack trace 활성화. `jdk.NativeMethodSample`/`jdk.ExecutionSample`은 10ms 주기. 각 실행 전 별도 recording에서 SQLite native callback → Java UDF → 30ms sleep 양성 대조군의 pin event를 반드시 확인한다. 이 대조군은 측정 recording에 포함하지 않는다.
- SQLite source ID/compile options, 클래스 로딩 위치, artifact hash, 실행 command, working-tree patch를 보존한다. 모든 DB/완료 snapshot의 `integrity_check=ok`를 요구한다.

### JFR 해석

[JEP 491](https://openjdk.org/jeps/491)은 JDK 24부터 monitor 보유 중 Java blocking이 carrier를 해제할 수 있게 한다. JNI 안의 OS sleep은 Java unmount 시도 없이 carrier를 점유할 수 있으므로 **pin event 0건 = carrier blocking 0건이 아니다**. 이번 판단은 JFR만이 아니라 동시 HTTP 요청과 독립 VT 지연을 함께 사용한다. NativeMethodSample 개수는 샘플링 증거이며 정확한 pinned 시간/CPU 점유율이 아니다.

이 애플리케이션은 Java 25 toolchain을 요구한다. JDK 21의 monitor pinning 개선을 이번 결과로 주장하지 않는다. Windows/Linux 결과도 아니다.

## 결과: carrier 4개

각 cell은 3회 실행의 중앙값이다. latency는 전체 데이터를 합친 percentile이 아니라 **각 실행 percentile의 중앙값**이다.

| 부하 | 드라이버 | 성공 req/s | HTTP p99 ms | 독립 VT start-delay p99 ms |
|---|---|---:|---:|---:|
| HTTP만 | xerial | 467.70 | 46.62 | 0.273 |
| HTTP만 | fork | 439.33 | 49.33 | 0.287 |
| 백업 1개 | xerial | 482.44 | 47.61 | 0.563 |
| 백업 1개 | fork | 440.44 | 50.64 | 0.257 |
| 백업 4개 | xerial | 298.09 | 855.27 | 815.31 |
| 백업 4개 | fork | 430.58 | 73.88 | 95.91 |

- HTTP만: fork 처리량 **6.1% 낮음**. 백업 1개: **8.7% 낮음**. carrier 여유가 있는 상태에서 전반적인 성능 개선은 확인하지 못했다.
- 백업 4개가 carrier 4개를 점유하는 조건: 독립 VT p99 **88.2% 감소**, HTTP p99 **91.4% 감소**, 처리량 **44.4% 증가**.
- 해당 조건의 독립 VT 최대 지연: xerial **934.44ms**, fork **227.89ms**. 3회 합산 100ms 초과 heartbeat는 **1,984 → 69건(96.5% 감소)**. fork도 nonblocking SQLite가 아니다.
- HTTP-only에서 프로세스 CPU/성공 요청의 중앙값은 **5.00 → 5.42ms**. CPU는 서버뿐 아니라 동일 JVM의 HTTP client/합성 upstream/JIT/계측까지 포함하므로 JDBC 단독 비용으로 해석하지 않는다.
- 18회 실행: HTTP **151,643건 성공**, HTTP 실패 0, source 호출 수 일치, backup 총 **270건 성공**, 모든 integrity check 정상. 백업 1개는 실행마다 9건, 4개는 36건 완료했다.

### Pin event와 carrier 점유는 다른 결과

4-carrier 본 matrix의 SQLite frame을 포함한 application pin event는 **양쪽 모두 0건**이었다. 일반 application pin event는 xerial 5건(합산 약 0.661ms), fork 2건(약 0.082ms)이고 수집 stack에 SQLite frame이 없었다. 이 작은 event 수 차이를 드라이버의 pinning 개선율로 제시하지 않는다. 별도 UDF 양성 대조군은 18회 모두 pin event를 기록했다.

백업 4개 조건에서 `NativeDB.backup` native sample은 xerial 3회 합산 **364개**, fork의 `NativeDB.backupStep` sample은 **43개**였다. event가 0이어도 xerial 쪽 VT 시작 지연은 약 815ms였다. 따라서 확인한 개선은 **native busy-wait로 인한 carrier starvation 완화**이며, “JFR pin event를 100% 제거했다”가 아니다.

fork의 잔존 native backup step/복사/I/O와 전체 페이지 스냅샷 조건을 무시하면 안 된다. 실제 복사는 native에서 수행하며, 더 많은 HTTP 요청이 성공하면 백업 대상 DB도 더 많이 증가한다. 같은 최초 seed에서 출발하지만 종료 DB 크기/성공 요청 수까지 강제로 같게 맞춘 실험은 아니다.


## 결과: carrier 10개 경계 확인

호스트의 논리 CPU 수와 같은 parallelism=10, maxPoolSize=10을 명시했다. JVM 기본 설정 그대로의 실행은 아니며, 나머지 조건은 4-carrier matrix와 같다.

| 부하 | 드라이버 | 성공 req/s | HTTP p99 ms | 독립 VT start-delay p99 ms |
|---|---|---:|---:|---:|
| HTTP만 | xerial | 478.16 | 45.04 | 0.058 |
| HTTP만 | fork | 426.07 | 54.82 | 0.057 |
| 백업 4개 | xerial | 460.30 | 57.83 | 0.062 |
| 백업 4개 | fork | 429.94 | 61.62 | 0.061 |

fork 처리량은 HTTP-only **10.9%**, 백업 4개 **6.6% 낮았다**. carrier가 남으면 이 부하에서는 xerial도 독립 VT 지연을 낮게 유지한다. 4-carrier의 이득을 모든 배포 환경의 처리량 개선으로 일반화할 수 없다.

두 matrix 총 **30 JVM 실행 / HTTP 258,752건 / 온라인 백업 486건**이 성공했다. HTTP 오류 0, source 호출 수 일치, 전체 integrity 정상, 양성 JFR 대조군 30/30 통과. 10-carrier의 application pin 2건도 class loading 경로였고 합산 약 0.127ms였다. JFR에서 실제 확인한 잔여 pin stack은 `BuiltinClassLoader.loadClassOrNull` 아래 MyBatis/Jackson이었다.

## 비교 식별과 해석 한계

- 두 드라이버의 SQLite source ID는 모두 `2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc`.
- SQLite compile options는 compiler 항목만 달랐다: 공개 xerial artifact `clang-11.0.1`, 로컬 fork native `clang-16.0.0`. 따라서 처리량 차이를 Java 코드 수정만의 비용으로 단정하지 않는다.
- xerial JAR SHA-256: `bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb`.
- fork macOS ARM64 native SHA-256: `3c762b0bea1989419eb65224a944145f66ba2fc6e1608d8f59aef72c29e396a8`.
- Gateway의 기존 미커밋 파일을 포함한 source/build input 131개는 `target/vt-wait-evidence/gateway-provenance-smoke/gateway-source.zip`에 보존했다. 이 추가 snapshot은 본 측정 후 생성했으며, input 파일 중 첫 본 측정 이후 수정된 파일은 없었다. source manifest SHA-256: `ebe5e6e56f6017adbf0b307e35b7735ff5d0e7514e5a4610d76d41d1fc694727`. 본 matrix 둘과 provenance smoke의 benchmark class SHA도 동일했다.
- 3회 × 20초의 로컬 실험이다. 120회 warmup이 완전한 JIT steady state를 보장하지 않는다. 신뢰구간을 산정한 장기 성능 검증이나 production SLA 보장이 아니다. 백업 1개에서 baseline 처리량이 HTTP-only보다 높게 나온 작은 차이도 개선이라고 해석하지 않는다.
- closed-loop client는 응답을 받은 뒤 다음 요청을 보낸다. open-loop 도착률, 외부 load-generator host, coordinated-omission 보정은 적용하지 않았다. throughput 및 request latency와 별개로 고정 주기 VT probe를 수집한 이유다.
- 백업 4개/750ms lock/4-carrier는 고갈을 의도적으로 만드는 stress 조건이다. 실제 app의 기존 기능에 4중 백업이 원래 존재한다는 의미가 아니다.
- generated BEGIN/COMMIT 경합 효과를 이번 HTTP 결과로 별도 입증하지 않았다. 이 애플리케이션은 writer pool=1/DEFERRED/WAL 구조이며, 이번 causal stress는 backup wait 경로다.

## 후속 과제 — 이번에는 최적화하지 않음

1. **비경합 transaction-control 비용 분리:** 정상 HTTP에서 6~11% 낮은 처리량과 CPU/요청 증가를 관찰했다. `DB.stepControl` → `busyTimeoutReadback`/`attemptNoWaitBusy` → `readBusyTimeout`의 PRAGMA prepare/step/finalize 및 handler 교체를 profiling할 후보로 남긴다. 현재 sample만으로 주원인이라고 확정하지 않는다. 먼저 같은 compiler로 만든 upstream/native 대조군을 추가한다. custom busy handler, live PRAGMA timeout, callback reentry 보존을 희생하는 fast path는 만들지 않는다.
2. **증분 백업 완료성/공정성:** 아래 pilot에서 xerial도 17초 백업이 발생했다. source-write 빈도/페이지 수/실제 restart 횟수를 동일하게 계측해 fork와 비교한다. timeout이나 retry 정책을 이번에 바꾸지 않았다.
3. **남은 native copy/I/O 점유:** fork도 포화 조건에서 VT p99 96ms, 최대 228ms였다. page 단위 복사, 동시 백업 수, fsync/파일 크기의 영향을 분리한다. 이 보고서만으로 page size 변경이나 executor offload를 제품에 적용하지 않는다.
4. **적용 범위 확장 검증:** 일반 DML busy wait, read-blocked COMMIT/BEGIN 경합을 사용하는 다른 실제 애플리케이션, JDK 21/24+, Linux/Windows를 별도 측정한다. 이번 결과는 macOS/JDK 25/Gateway에 한정한다.

수치의 실행별 원본 요약은 [application-benchmark-results.json](application-benchmark-results.json)에 둔다. 소스 runner는 [GatewayBenchmark.java](../../scripts/benchmark/GatewayBenchmark.java), [run-gateway.py](../../scripts/benchmark/run-gateway.py)다.

## 예비 실행에서 발견한 한계

`target/vt-wait-evidence/gateway-smoke/`의 4초 smoke는 비교기, JFR 양성 대조군, HTTP outcome, DB 무결성을 통과했다. 짧은 smoke 수치는 최종 성능 수치로 사용하지 않는다.

첫 20초 matrix(`target/vt-wait-evidence/gateway-benchmark/`)는 128페이지씩 복사하는 incremental backup을 사용했다. 정상 HTTP 6회 후 xerial의 백업 1개 시나리오에서 백업 2회만 완료되었고, 최대 17,134ms가 걸렸다. 측정기의 future 10초 기준을 넘겨 `TimeoutException`을 기록하고 nonzero exit로 중단했다. 이 실행의 HTTP 7,827건은 모두 성공했고 DB integrity는 정상이다. 실패 산출물을 삭제하거나 성공으로 재분류하지 않았다.

[INFERENCE] 지속적인 source DB 쓰기에 따른 incremental backup 재시작이 유력 후보지만, 재시작 횟수는 계측하지 않았으므로 확정된 원인이나 fork 고유 결함이라고 할 수 없다. 본 matrix는 이 변수를 분리하기 위해 양쪽 모두 `pagesPerStep=-1`로 변경한다. 페이지 크기를 고정한 채 이 실패를 없앤 드라이버 수정은 하지 않았다. 원래 부하는 `--backup-pages 128`로 재현 가능하다.

## 재현

Gateway 위 revision과 당시 미커밋 source snapshot, JDK 25, Python 3, Maven/Gradle 의존성이 필요하다. 매번 새로운 output 디렉터리를 사용한다.

```sh
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.2-tem"
python3 scripts/benchmark/run-gateway.py --prepare \
  --gateway ../gateway \
  --output target/vt-wait-evidence/gateway-snapshot-c4 \
  --seconds 20 --clients 12 --carriers 4 --repetitions 3 --scenarios 0 1 4

python3 scripts/benchmark/run-gateway.py --prepare \
  --gateway ../gateway \
  --output target/vt-wait-evidence/gateway-snapshot-c10 \
  --seconds 20 --clients 12 --carriers 10 --repetitions 3 --scenarios 0 4
```

`--prepare`는 Gateway test classes(기존 fixture 재사용)와 현재 fork classes를 빌드한다. 애플리케이션/드라이버 소스는 수정하지 않는다. native library는 현재 작업 트리에 빌드된 macOS ARM64 resource를 사용한다.

원시 산출물은 ignored `target/vt-wait-evidence/`에 둔다. 각 실행의 `command.json`, `stdout.log`, `result.json`, `application.jfr`, `positive-control.jfr`, HTTP/VT/백업 latency CSV, SQLite DB/snapshot과 matrix별 `environment.json`, `fork-working-tree.patch`, `results.json`, `summary.json`이 증거다. SQL/JNI/애플리케이션의 추가 tracing으로 측정 경로를 바꾸지 않는다.
