# GitHub Actions: 동일 toolchain 실제 앱 벤치마크

**최종 비교 완료:** [run 36304158851](https://github.com/Clickin/sqlite-jdbc/actions/runs/36304158851), 측정 commit `fefbb7431736c9c6ed4e59f0893c49a25327293e`. 36개 측정이 모두 있으며, **CI conclusion은 failure**다. xerial의 백업 stress 6회에서 실제 요청 실패 104건을 기록했기 때문이다. before/fork에는 요청 실패가 없었다. 실패를 제거하거나 green으로 재분류하지 않았다.

최적화 후 fork는 정상 부하에서 matched xerial보다 **중앙값 처리량 4.79% 낮다**. 백업 경합에서는 **성공 처리량 65.54% 증가**, 독립 VT p99 지연 **56.38% 감소**, 요청 실패 **104 → 0건**이었다. 따라서 carrier 고갈 완화는 확인했지만, 비경합 성능 동등 이상이라는 최종 목표는 아직 충족하지 못했다.

## 완료 통지 방식

GitHub의 [`workflow_run` webhook](https://docs.github.com/en/webhooks/webhook-events-and-payloads#workflow_run)은 `completed` 이벤트를 제공하지만 외부 HTTP receiver가 필요하다. 실행 시점 `Clickin/sqlite-jdbc`에 등록된 repository webhook은 없었다.

별도 webhook/tunnel 서비스를 추가하지 않고 [wait-actions.py](../../scripts/benchmark/wait-actions.py)를 background process로 실행한다. 명시한 repository·commit SHA·workflow에 대해 30초 간격 REST 조회를 내부에서 수행하고, 완료 시 JSON **한 번만 stdout에 출력**한다. 도구의 process-completion callback이 이를 assistant에 전달한다. **GitHub push webhook 구현이라고 주장하지 않으며, 모델이 `gh run watch` 진행 출력을 반복 소비하지 않는다.**

기존 완료 run `xerial/sqlite-jdbc/actions/runs/35676034220`으로 observer를 smoke 실행했다. REST 1회 후 성공 결론과 SHA가 포함된 단일 callback을 받았다. 원시 확인값은 `target/vt-wait-evidence/ci-callback-smoke.json`이다.

## 공개 입력과 비교군

사용자가 Gateway source snapshot의 공개를 승인했다. fixture에는 source/build input 131개만 포함한다. 운영 DB, 실행 중 생성된 token/key, `.gradle`, 사용자 home/인증 설정을 넣지 않았다. credential marker 검색 결과는 기존 synthetic test sentinel뿐이었다.

- Gateway source: [gateway-source.zip](../../scripts/benchmark/fixtures/gateway-source.zip). 기존 실험과 같은 local Gateway HEAD `885c6b8b28a61c479986b55f405c17ac4fade6f9` + 당시 미커밋 source snapshot.
- 정확한 파일 hash: [manifest.json](../../scripts/benchmark/fixtures/manifest.json).
- `xerial`: release 3.53.4.0의 고정 commit `cab7981c19ce04d691f0675f0b2586afc2bbf803`에서 Java/JNI를 새로 빌드.
- `before`: 현재 fork에서 [pre-optimization.patch](../../scripts/benchmark/fixtures/pre-optimization.patch)를 적용해 초회 timeout 중복 조회 제거 직전의 세 source file을 복원. Linux 빌드에서 드러난 `<stdint.h>` 누락만 before/fork 양쪽에 동일하게 보정했으며, 예상 SHA와 일치해야 한다.
- `fork`: 코드 리뷰 수정과 중복 조회 제거를 포함한 현재 fork source.

## 공정성 통제

[Workflow](../../.github/workflows/vt-benchmark.yml)와 [build-ci.sh](../../scripts/benchmark/build-ci.sh)는 다음을 강제한다.

1. **한 `ubuntu-24.04` job 안에서 전부 순차 실행.** 서로 다른 matrix runner의 CPU/디스크를 비교하지 않는다.
2. **Temurin 25.0.2, 같은 javac, compiler-plugin 3.16.0, release 8, GCC 13.** 공개 JAR의 다른 compiler 결과를 기준선으로 사용하지 않는다. upstream/fork POM의 compiler-plugin 버전 차이도 직접 동일 goal 호출로 제거한다.
3. **SQLite는 한 번만 compile.** upstream에서 만든 동일 `sqlite3.o`를 before/fork JNI에도 링크한다. object hash가 변하지 않았고 다른 SQLite object를 만들지 않았는지 검사한다.
4. source/fixture hash, compiler/JDK version, 실제 build command, JNI header/object/library hash, Java class major version 52, runner CPU/memory/filesystem을 남긴다.
5. runtime `sqlite_source_id()`와 `PRAGMA compile_options`가 모든 variant에서 같아야 한다. 실제 driver classpath/native override를 기록한다.
6. app build daemon 종료 후 측정한다. Gradle/Kotlin daemon이 남으면 중단한다.
7. JVM마다 새 DB와 같은 fixture를 사용한다. **동일한 6,000건의 HTTP warmup**으로 시작 전 DB 작업량을 맞추고 실제 warmup 경과 시간을 기록한다. 기존 120건 warmup보다 강화했지만, 이를 자동으로 완전한 steady state 증명으로 취급하지 않는다.
8. 12 client, carrier 4, heap 512 MiB, 측정 30초, 조건별 6회. `xerial → before → fork`, `before → fork → xerial`, `fork → xerial → before` 순환을 두 번 반복한다.
9. HTTP-only와 온라인 백업 4개 조건을 분리한다. 백업 destination lock 750ms, 2초 간격, 전체 페이지 복사라는 기존 stress 조건을 유지한다. Gateway production 코드를 바꾸지 않는다.
10. HTTP outcome/source call 수, positive JFR pin control, 각 DB/snapshot integrity를 확인한다. 성공한 실행의 생성 DB만 삭제하고 JFR/CSV/JSON/log는 artifact로 보존한다.
11. 측정이 완결되어 결과 파일이 있는 요청/백업 실패는 **실패한 관측값으로 보존하고 다음 variant도 실행**한다. 모든 결과를 만든 뒤 failed run이 하나라도 있으면 process와 CI가 nonzero로 종료한다. warmup/빌드/identity/무결성 오류로 완결된 측정 자체가 없으면 즉시 중단한다. 오류를 성공으로 재분류하거나 애플리케이션 timeout을 늘리지 않는다.

GitHub-hosted runner도 가상화/host 부하에 따른 편차가 있을 수 있다. [GitHub-hosted runner 문서](https://docs.github.com/en/actions/concepts/runners/github-hosted-runners)를 기준으로 runner image와 실제 hardware를 기록하며, 전용 물리 장비나 완전 무잡음 환경이라고 주장하지 않는다. Linux 결과의 절대값을 앞선 macOS 결과와 직접 speedup으로 비교하지 않는다.

## 로컬 smoke와 실행 경로

Python/bash/YAML syntax를 확인했고, ZIP만 추출한 Gateway를 대상으로 로컬 3-way smoke 6회를 실행했다. 총 warmup 121건을 12 client에 정확히 나누는 비균등 분배 경계도 통과했다. 실제 VT/HTTP 결과, 일치하는 compiler option/source ID, 검증 후 DB 삭제를 확인했다. 이 짧은 로컬 smoke 수치는 CI 성능 결과로 사용하지 않는다.

전용 branch는 `benchmarks/vt/20260927-compiler-parity`다. 기존 `ci` branch와 ref namespace가 충돌하여 `ci/...` 이름은 사용하지 않았다. 기존 일반 CI는 이 benchmark branch를 제외하며, benchmark workflow만 실행한다. 원래 작업 브랜치/미커밋 작업은 유지한다.

실행 후에는 completion JSON의 정확한 run ID로 artifact를 다운로드하여 분석한다. 성공 여부는 callback의 conclusion만으로 끝내지 않고, build identity·runtime options·반복별 결과·outcome 검사를 함께 확인한다.

## 실행 중 검출하고 수정한 문제

| Run | GitHub conclusion | 산출물 판정 |
|---|---|---|
| [36301957879](https://github.com/Clickin/sqlite-jdbc/actions/runs/36301957879) | failure | Linux/GCC에서 fork의 `intptr_t` 선언 누락 검출. before/fork 모두 `<stdint.h>`를 명시하도록 수정. 측정 전 실패. |
| [36302244825](https://github.com/Clickin/sqlite-jdbc/actions/runs/36302244825) | success | **전체 실험 성공으로 인정하지 않음.** 정상 부하 18회 후 첫 xerial stress에서 요청 실패 20건(`HTTP_CONCURRENCY_LIMIT`)이 발생해 비교기가 중단됨. `tee`가 exit code를 가린 workflow 결함도 확인. |

두 번째 run의 실제 결과는 HTTP 200 안의 MCP `isError=true` business failure였다. xerial stress에서 성공 713건, 실패 20건, backup 56건 완료, integrity 정상, 독립 VT p99 1,812.93ms였다. 정상 부하 18회는 같은 source ID/compile options/shared SQLite object를 사용했지만, before/fork stress 결과가 없으므로 이 부분 실행으로 전체 비교 결론을 만들지 않는다.

수정 후 workflow는 명시적 `shell: bash`의 `pipefail`을 사용하고, summary가 없으면 실패한다. 비교기는 failed outcome도 기록하며 전체 matrix 종료 후 실패를 반환한다. 별도 로컬 failure-propagation smoke에서 DB lock을 4초 유지하여 실제 `SQLITE_BUSY`를 발생시켰다: before가 실패해도 fork가 실행되었고, 세 결과/실패 DB를 보존한 뒤 aggregate exit 1을 확인했다. 이 강제 실패 smoke는 CI의 750ms lock 설정이나 production 코드를 변경하지 않았다.

완료 통지는 각 시도당 한 번이었다. 첫 run은 내부 REST 5회/122초, 두 번째는 52회/1,554초 동안 기다렸으며, 진행 로그를 모델에 반복 전달하지 않았다.

## 최종 환경·완결성 검증

- Ubuntu 24.04.5 LTS, runner image `20260920.314.1`, Microsoft Hyper-V VM.
- guest 보고 CPU: AMD EPYC 7763, 4 vCPU(guest topology 2 cores × 2 threads), MemTotal 16,373,452 KiB.
- Temurin/Javac **25.0.2+10 / 25.0.2**, compiler-plugin **3.16.0**, bytecode release **8**, GCC **13.3.0 (Ubuntu 13.3.0-6ubuntu2~24.04.1)**, Maven 3.8.7.
- 36회 모두 source ID 및 compile options 동일. shared `sqlite3.o` SHA-256: `52c0c3e3fcdf73822de1808b2b87dd209d52acc483ced083b911190bf34c43de`.
- Native library SHA-256:
  - xerial: `8a262774b9c10ec24caece841756e0ac492701d04ae779335a29978fcf706b58`
  - before: `05cc8a121d7d0c2407c72b90fb3c9cf36c65bc686b4811131bec59af935d8c60`
  - fork: `059c6b70bf8ffefdbb865144ba2669ef7b9e9fdddc7e824dde46aed1a6df55cd`
- warmup은 실행마다 정확히 6,000건, 실제 **46.48–51.17초**. 측정 elapsed는 마지막 요청 drain을 포함해 **30.038–30.064초**.
- 6개 cell × 6회 = **36회 완결**. 측정 요청 **121,515건 = 성공 121,411 + 실패 104**, 별도 warmup 성공 216,000건, backup API 성공 **1,008건**.
- 모든 실행의 source 호출 수가 성공 요청 수와 일치했고, source DB와 최종 backup 파일의 `integrity_check`가 정상이다.
- JFR 양성 대조군 36/36에서 SQLite frame의 pin을 확인했다. 측정 구간 `jdk.VirtualThreadPinned`는 **모든 variant에서 0건**이다.
- runner filesystem은 ext4이며 `nobarrier,data=writeback,journal_async_commit` 등이 설정되어 있었다. 같은 VM 안의 상대 비교 조건은 같지만, SQLite `synchronous=FULL` 설정만으로 이 환경의 전원 장애 내구성이나 production storage 성능을 입증하지 않는다.

## 결과

표는 실행별 값의 중앙값이며, 요청 실패는 6회 합계다. HTTP latency는 실패 요청도 포함한다.

| 부하 | 드라이버 | 성공 req/s (min–max) | HTTP p99 ms | 독립 VT p99 ms | 요청 실패 |
|---|---|---:|---:|---:|---:|
| HTTP만 | xerial | 141.84 (136.56–143.83) | 153.54 | 0.707 | 0 |
| HTTP만 | before | 133.51 (130.99–134.90) | 163.76 | 0.855 | 0 |
| HTTP만 | fork | 135.04 (133.55–137.66) | 163.61 | 0.812 | 0 |
| 백업 4개 | xerial | 61.47 (60.12–63.26) | 1,362.07 | 1,205.27 | 104 |
| 백업 4개 | before | 100.86 (98.30–101.73) | 695.03 | 482.25 | 0 |
| 백업 4개 | fork | 101.76 (99.97–102.55) | 694.68 | 525.76 | 0 |

### 해석

1. **이번 실행은 이전 로컬/부분 CI보다 안정적이었다.** cell별 req/s CV는 **0.86–2.21%**였다. 단일 VM 6회 반복에서 관찰한 안정성이지, 모든 GitHub runner의 무잡음 보장은 아니다.
2. **정상 부하 성능 gap은 남았다.** fork/xerial 중앙값 비율은 −4.79%이고, 같은 repetition으로 짝지은 비교에서도 6회 모두 fork가 느렸다. 정상 부하의 프로세스 CPU/성공 요청 중앙값도 xerial 13.64ms, before 14.63ms, fork 14.42ms였다. CPU에는 client/합성 source/JIT/계측이 포함된다.
3. **중복 조회 제거는 작은 회복이었다.** fork/before 정상 처리량 중앙값은 +1.15%; paired 기하평균은 +1.55%였다. 개선은 6쌍 중 4쌍이며, 모든 실행에서 빨라졌다는 주장은 하지 않는다. 6쌍의 exact empirical bootstrap 95% 구간은 약 +0.26–+2.76%이나, 단일 VM의 탐색적 구간이며 독립 host 표본의 신뢰구간은 아니다.
4. **native-only 대비 경합 효과는 훨씬 컸다.** fork의 성공 처리량 +65.54%, HTTP p99 −49.00%, 독립 VT p99 −56.38%. xerial stress 실패율은 **104/11,207 = 0.928%**, 오류는 모두 `HTTP_CONCURRENCY_LIMIT`였다. 같은 부하에서 before/fork는 0건이다.
5. **pin event 감소율로 바꾸어 말하지 않는다.** JFR pin은 모두 0인데도 native-only의 독립 VT p99는 약 1.2초다. 개선 증거는 native 대기 동안의 carrier 진행성이다. fork도 최대 VT 지연 **674.66ms**가 남아 native copy/I/O까지 nonblocking이 된 것은 아니다.
6. **이번 작은 최적화가 backup 진행성을 개선했다고 주장하지 않는다.** before/fork backup VT p99 범위가 겹치고 중앙값은 오히려 482 → 526ms다. 큰 차이는 원래 native-only 대조군과 Java-wait fork 사이에 있다.

## 산출물과 재현

- [기계 판독 결과 및 실행별 값](ci-benchmark-results.json)
- [측정 workflow](https://github.com/Clickin/sqlite-jdbc/actions/runs/36304158851)
- artifact `gateway-benchmark-36304158851-1`, ID `10927871605`, 11,286,828 bytes, 보존 기간 30일.
- artifact digest: `sha256:061a7b00dfded2e431e2711e9f5cef077c1ce191fe6148dbcc5c5bd93ab9f023`.
- 다운로드된 원시 파일: `target/vt-wait-evidence/github-36304158851/target/vt-wait-evidence/ci-benchmark/`.
- 원시 파일에는 build identity/log, source manifests, per-run argv/result/JFR/latency CSV가 있다. 생성 DB는 upload하지 않았다.
- 최종 완료 observer는 내부 REST **112회 / 3,383.9초** 후 **알림 한 번**만 반환했다. GitHub conclusion `failure`를 받은 뒤 결과 36개와 모든 group/identity/outcome을 별도로 검증했다.

```sh
gh run download 36304158851 --repo Clickin/sqlite-jdbc \
  --name gateway-benchmark-36304158851-1 \
  --dir target/vt-wait-evidence/github-36304158851
```

새 비교는 고정된 source fixture와 workflow를 같은 방식으로 실행한다. 공개 benchmark branch의 push가 실행 trigger다. 결과 보고서만 갱신하는 commit은 `[skip ci]`로 별도 긴 측정을 발생시키지 않는다. 기존 macOS 수치와 이번 Linux 절대값을 혼합하지 않는다.
