> 최종 정리본(2026-09-30, 인계받은 에이전트가 기존 로그·XML만으로 정리). 추가 실험은 하지 않았다. 원래 CI의 정확한 서명(`frame::interpreter_frame_method()+0xe`)은 재현하지 못했다.

# sqlite-jdbc riscv64 CI 일회성 조사

대상: Clickin/sqlite-jdbc `vt-java-wait`, 실패 기준 `06bc3b90a867376a7c288c561f2574d82550e758`. 조사 날짜: 2026-09-30 UTC. 원격 저장소 변경, Actions 실행/재실행, 이슈/PR, 예약 작업은 수행하지 않았다. 제품 코드는 변경하지 않았다.

## 결론과 판정 범위

실제 클라우드 amd64 Linux에서 CI와 같은 riscv64 Ubuntu 이미지, QEMU 10.2.3 바이너리, Ubuntu JDK 25.0.4.1 패키지를 확보하고 ProcessBuilder 자식 JVM을 검증한 뒤 빌드와 테스트를 실행했다. 원형 commit-progress 자식의 exit 134/SIGSEGV와 클래스 전체 실행의 backup-progress VM crash를 재현했다. 그러나 이 두 crash의 서명은 CI의 `frame::interpreter_frame_method()+0xe`와 다르다. 원래 CI와 동일한 서명을 재현했다고 주장할 수 없다.

원래 begin-progress exit 1의 실제 stderr는 GitHub 로그에 없고 run artifacts도 비어 있다. 유효한 원형 begin Maven 실행은 통과했다. 진단 반복 실행에서 관찰한 Java 예외는 별도 증거이며, 원래 begin 실패의 원인으로 확정하지 않는다.

최종 반복 결과와 추가 실험은 아래 표와 `results.csv`, `results.jsonl`, `pure-results.jsonl`에 기록한다. JFR off의 통과 여부만으로 JFR 단독 원인을 확정하지 않는다. HotSpot/JFR, QEMU, 게스트 라이브러리 또는 상호작용의 구분이 남는다.

## 원본 CI 로그 대조

원본 [job](https://github.com/Clickin/sqlite-jdbc/actions/runs/36597603222/job/109506429882)의 로그를 읽어 `evidence/ci-job.log`로 보존했다. 사용자 제공 이미지 digest, JDK, Maven, QEMU 및 환경변수와 대조했다. CI는 VtCarrierProgressTest 5개 중 2개 실패, 약 315.6초였고 전체는 519 tests / 2 failures / 24 skipped였다. begin은 자식 exit 1, commit은 exit 134 / `libjvm.so+0x6406b8`, `frame::interpreter_frame_method() const+0xe`. 원본 `/work/hs_err_pid703.log` 전체와 begin stderr는 공개 로그/다운로드 가능한 artifacts에서 얻지 못했다.

현재 branch HEAD는 `c6a16c99a3277c2de358e4f25df9b831863af035`로 별도 기록했다. 이후 변경에 있는 multiarch VT 클래스 전체 skip은 이 조사에 적용하지 않았다.

## 실제 환경과 차이

| 항목 | 실제 실행 | 원래 CI와의 차이 |
|---|---|---|
| 호스트 | x86_64 Ubuntu 24.04.3, kernel 6.18.44 | Docker/daemon/socket 없음 |
| 자원 | nproc 9, cgroup CPU 8개, 메모리 제한 8 GiB, 32 GiB filesystem | GitHub runner와 다름 |
| 실행 경로 | 격리 user/mount namespace의 binfmt POCF + chroot + QEMU user-mode | Docker/run-on-arch-action을 직접 사용하지 않음 |
| 게스트 | Ubuntu Resolute 26.04.1, glibc 2.43 | apt 설치 패키지 전체가 CI와 완전히 같지는 않음 |
| JDK | Ubuntu riscv64 25.0.4.1+1-1~26.04.4 | headless 패키지, dpkg-deb 추출; maintainer scripts 미실행 |
| QEMU | 10.2.3, 제공된 binfmt digest에서 추출 | 동일 digest amd64 플랫폼 바이너리 |
| Maven | 게스트 3.9.12-1, native upstream 3.9.12 | 최초 native 기준은 배포판 3.8.7도 사용 |
| 사용자 | 게스트 root; native 전체 suite는 uid1001:121 | CI 게스트 uid1001:121 |
| native 대조 | Ubuntu amd64 JDK 25.0.4+7-1~24.04 | vendor 같으나 build와 아키텍처 다름 |

고정 Ubuntu index digest: `sha256:476490842b11d41f667ada0e63134564cc78ab5250422b28340cfc375823cb49`, 선택한 riscv64 manifest `sha256:06ba91d70d79ee5df6b2c9b9b696ff0537cedef0ae14109851611d29d2fa6c66`.

고정 binfmt index digest: `sha256:400a4873b838d1b89194d982c45e5fb3cda4593fbfd7e08a02e76b03b21166f0`, 선택한 amd64 manifest `sha256:465d3fdd28d0f2b871ba4b4ec98bd183292e96167f00d9fd40bd249f8632d705`.

QEMU hash `3ddd4d6ec959cbf040176a335e33fd6c21d1097073d686b8fff02cd516e083b4`.
JVM java hash `28c649e86d4c07e95df896f5582ba3388b4e37f2486770a3dd36d70ab8feab4a`.
libjvm hash `5bdada3927debb2995e683a67e4c91a7f3cb5f65bd9a39373e3c0f9ca83f2f09`.
실제 로드한 JDBC riscv64 라이브러리 hash `2869d15bf66ac0d7b097178039016c6c66de530cbb4db39ed72fbc935f34a6e8`는 저장소 라이브러리와 일치한다. 새 native 빌드는 하지 않았다. 실제 PID의 maps/argv/hash는 `evidence/loaded-libraries-fixed`에 있다. 전체 패키지와 바이너리 정보는 manifest 참조.

GitHub/Ubuntu/Maven/OCI 다운로드는 승인된 클라우드 네트워크 경로에서 성공했다. 게스트 Maven 온라인 의존성 해석은 실패했으므로 native Maven에서 동일 pom 의존성을 준비하고 게스트 Maven을 offline 실행했다. 기본 제한 경로의 차단과 승인 경로 성공을 구분했다. 전역 binfmt를 바꾸지 않았고 테스트 프로세스에 privileged 컨테이너 권한을 주지 않았다. PRoot 경로는 실행파일/argv0 처리로 실패하여 채택하지 않았다.

## 원형 빌드와 산출물 검증

`source`는 실패 SHA의 무수정 checkout, `diagnostic`은 같은 SHA에서 만든 별도 worktree다. 최초 guest Maven 실행에서 로컬 시나리오 class 파일이 9 bytes로 잘려 ClassFormatError가 난 실행은 **무효 준비 실행**으로 분리했다. 당시 workspace 실행 중 파일/로그/소유권 변화도 관찰했지만 그 원인은 확정하지 않았다. CI에서 다른 시나리오가 실행됐다는 점과도 달라 이것을 CI begin 원인으로 사용하지 않는다.

계산을 `/tmp/sqlite-riscv-lab`로 옮기고 올바른 산출물을 재생성한 뒤 원래 guest Maven 경로를 다시 실행했다. Java 8 `--release 8 -g` 시나리오 hash는 native와 동일한 `6d46b648847660b3a2e5840bc6e9236450c8534e5174a0fc6f005757efad14c2`였다. 제품 클래스 132개의 guest/native byte hash가 일치한다. Java 9 multi-release 소스 등 pom 구성은 유지했다. 반복 실행은 이미 검증한 클래스와 같은 의존성으로 JUnit Launcher를 사용하며 최초 Maven 빌드와 구분한다. 진단 parent 2개 클래스만 추가 변경된다.

## 실행 결과

| 조건 | 실제 결과 |
|---|---|
| 원형 guest Maven begin 단독 | 1회 통과 (원래 옵션/timeout 유지) |
| 원형 guest commit 단독 (JUnit Launcher) | exit 134, `libz.so.1+0x78e2` inflate crash (SQLite 로드 전) |
| 원형 guest 클래스 전체 (Launcher) | begin/commit/배타성 통과, backup-progress에서 continuation thaw crash |
| A: 동일 QEMU 10.2.3/JDK 25.0.4.1, JFR on, 대상 2개 메서드 각 5회 | begin 4 통과 / 1 VM crash; commit 3 통과 / 2 Java 예외 |
| B: 동일 환경, JFR off, 각 5회 | 10/10 통과 |
| C: native Ubuntu JDK 25.0.4, JFR on, 클래스 5회 | 25/25 통과 |
| D: child -Xint + JFR on, 각 2회 | begin 2 통과; commit 1 통과 / 1 `KnownOIDs` NoSuchFieldError |
| pure Java (SQLite 없음) JFR on 2회 | 2/2 `ThawBase::recurse_thaw_compiled_frame` crash |
| pure Java JFR off 2회 | 2/2 통과 |
| pure Java, Java+native sampling 모두 off 2회 | 2/2 통과 |
| pure Java, `jdk.ExecutionSample`만 off 2회 | 2/2 통과 |
| pure Java, `jdk.NativeMethodSample`만 off 2회 | 2/2 통과 |
| pure Java, QEMU 8.2.2 + 같은 JDK + JFR on 2회 | 2/2 통과 (ProcessBuilder parent/child 포함) |
| 최종 진단 패치 native full suite (uid 1001:121) | 519 tests, 0 failures, 0 errors, 24 skipped |
| 최종 진단 패치 riscv64 guest full suite (root) | 519 tests, 4 failures, 0 errors, 24 skipped; BUILD FAILURE, 17:56 min, 프로세스 exit 코드 미수집 |

해석 상의 주의: 위 표는 소표본이다. pure Java에서 JFR on만 crash하고 sampling 단일 이벤트를 끈 경우는 통과했지만 n=2이므로 sampler 원인으로 확정하지 않는다. QEMU 8.2.2 통과와 10.2.3 crash의 차이는 QEMU 구현과 노출 CPU 기능(10.2.3에 `zfh zfhmin zvfh` 추가)을 분리하지 못했다.

### 마지막 guest full suite 실패 4건 분석

| 테스트 | 원인 판단 | 근거 |
|---|---|---|
| `ErrorMessageTest.writeProtected` (58행) | **실행 사용자 root 때문에 발생한 환경 아티팩트**. 제품 결함 아님 | `File.setReadOnly()` 후 쓰기가 예외를 내야 하는데 root는 읽기 전용 파일에도 쓸 수 있어 "Expecting code to raise a throwable". native suite는 uid 1001:121에서 통과 |
| `ConnectionTest.openNonExistingFileInReadOnlyDirectory` (463행) | 위와 동일. 읽기 전용 디렉터리에 root는 `Files.createFile` 성공 → `AccessDeniedException` 미발생 | 테스트 자체가 SQLite 호출 전 assertion에서 실패. native uid 1001에서는 통과 |
| `SQLiteJDBCLoaderTest.test(Path)` (138행) | **에뮬레이션 지연에 따른 타이밍 실패로 판단**(직접 확인은 미완). 32스레드 완료 여부를 `awaitTermination(3, SECONDS)` 후 검사하는데 완료 0개 | 이 테스트가 riscv64 guest에서 16.281초 소요(native는 전체 suite가 25초). 3초 대기 한도를 크게 초과. 제품 코드 결함 근거 없음 |
| `VtCarrierProgressTest.backupJavaWaitKeepsSameConnectionExclusive` (175행) | VM crash. child exit 134, SIGSEGV `libz.so.1+0x78e2` | `backup-exclusion` 시나리오 hs_err. 원형 commit 단독에서 본 libz inflate 서명과 동일 계열. SQLite 로드 전 클래스 로딩 중 crash이므로 SQLite JNI 무관 가능성이 높음 |

정정: handoff의 요약은 이 마지막 crash를 backup-progress로 적었으나, 실제 실패 메서드는 `backupJavaWaitKeepsSameConnectionExclusive`이고 evidence 디렉터리는 `backup-exclusion-*`이다. 같은 실행에서 begin/commit/control/backup-progress 시나리오는 통과했다.

권한 관련 2건과 타이밍 1건은 root guest·QEMU 지연에서 비롯된 것으로 보이며, uid 1001로 guest를 다시 돌려 확인하는 것은 미실행이다. 결론적으로 실질적 VM 문제는 libz/continuation thaw crash 계열뿐이다.

각 반복은 새 parent/child JVM과 JUnit TempDir/DB를 사용한다. carrier parallelism/maxPoolSize 모두 1, 원래 자식 timeout 120초, SKIP_TEST_MULTIARCH=true, MAVEN_OPTS=-Xmx2g를 유지했다. stdout/stderr/exit/command/JFR/hs_err는 source clean과 분리된 evidence에 보존했다. 진단 후 JFR-on 결과와 원형 결과를 함께 기록하므로 진단 자체의 timing 효과를 배제했다고 주장하지 않는다.

## 확보한 crash 분석

원형 commit 단독의 `hs_err_pid92008.log`: main JavaThread `_thread_in_native`, problematic `libz.so.1+0x78e2`, `inflate` → `libzip` → `Inflater` → SLF4J/JDBC 클래스 초기화 → DriverManager. fault address `0x5d`. 이 프로세스 hs_err의 loaded library 목록에는 SQLite JDBC native library가 없다. 따라서 **이 압축 해제 crash에는 아직 SQLite JNI가 실행되지 않았다**. 원래 CI의 libjvm crash에는 이 결론을 확장하지 않는다. 오류 보고 중 register/stack 출력에도 추가 SIGSEGV가 있어 hs_err 일부가 불완전하다.

원형 클래스 실행의 `hs_err_pid93323.log`: carrier `ForkJoinPool-1-worker-1`, `_thread_in_Java`, `ThawBase::recurse_thaw_interpreted_frame()+0x156` → `Thaw::thaw_slow` → continuation thaw stub → `Continuation.run` → `VirtualThread.runContinuation`. fault address `0xdda0`. 이 프로세스에는 SQLite JDBC가 로드돼 있다. continuation 복원 문제를 지지하지만 그 앞의 JNI 메모리 손상을 이 stack만으로 배제하지 못한다. crash thread stack에 JFR sampler 프레임은 없다. 이것은 JFR 상호작용을 배제하는 근거도 아니다.

진단 A 반복의 commit exit 1은 stderr상 `NoSuchFieldError: SQLiteConfig$Pragma.DATE_PRECISION`이며, 로컬 class에는 해당 field가 실제 존재한다. 디스크 제품 클래스 132개가 원형과 일치하고 다른 JVM에서 같은 파일로 통과했다. 이 실행은 Java 예외로 분류하고 VM crash와 분리한다. 원래 CI begin stderr가 없는 상태에서 같은 예외로 간주하지 않는다.

실제 libjvm의 `frame::interpreter_frame_method` symbol은 `0x6406aa`여서 CI offset `+0xe = 0x6406b8`와 일치하는 바이너리를 확보했다. 하지만 동일 symbol에서 crash를 관찰했는지는 추가 결과를 따른다. 원본 전체 stack가 없어 CI crash thread/JFR sampling/continuation/JNI 경계를 직접 확인할 수는 없다.

## 코드 판정과 최소 수정

Java busy-wait는 synchronized 배타성을 유지한 채 no-wait JNI 호출 뒤 Java sleep을 수행한다. JNI busy marker는 호출별 stack context를 설치하고 handler/timeout을 복원한다. 선언과 export signature를 확인했으며 이 조사에서 제품 구현 결함을 확정할 증거는 얻지 못했다. 동시성 검증 실패를 숨기거나 carrier 증가, 플랫폼 스레드 풀, lock 전면 교체를 적용하지 않는다.

최소 수정은 **테스트 진단 보강**이다. stdout/stderr 모두 assertion에 포함, 자식/reader 명령행과 ErrorFile, 경과 시간/종료 분류 보존, reader timeout 강제 정리, clean 밖 evidence 경로, 테스트 전용 JFR on/off와 child 옵션. 기본 JFR 설정과 주 진행성/connection 배타성 검증은 유지한다. 기존 multiarch 예외는 JFR reader 실패 허용이며 시나리오 recording 끄기와 다르다. JFR off는 조사 조건일 뿐 현재 권장 제품 수정이나 확정 우회가 아니다.

## 재실행과 남은 검증

별도 클라우드 Linux 디렉터리(권장 `/tmp/sqlite-riscv-repro`)에 evidence 묶음을 풀고 `investigation/README.md`의 준비/빌드/원형/반복 명령을 실행한다. 제한된 플랫폼에서 namespace/binfmt mount가 금지되면 차단 결과를 남기고 중단한다. 다른 JDK/QEMU로 자동 교체하지 않는다.

## 남은 검증 (미실행)

- 동일 CPU 기능을 맞춘 QEMU 10.2.3 vs 8.2.2 비교, 다른 JDK 25 build, 원래 CI 서명 재현.
- guest를 uid 1001:121로 실행해 권한 실패 2건 소거 확인, loader 테스트의 3초 한도 초과 여부 재확인.
- `qemu-system` 전체 시스템 에뮬레이션, `-Xcheck:jni`.
- 재개하려면 `/tmp/sqlite-riscv-lab`이 사라졌으므로 `scripts/prepare.sh`와 README로 재구축해야 한다.

로컬 진단 commit: `6616871b8315e9f01ace958f04cac89415b9e2a9` (branch `investigation/riscv64`, 원격 push 없음; 이 commit은 원 작업 환경에만 있고 묶음에는 patch로 포함). patch와 format-patch를 함께 제공한다. 대용량 rootfs, OCI layers, dependency cache, core dump는 Git/묶음에 넣지 않았다. 원본 stdout/stderr, hs_err, JFR 및 주요 Surefire reports만 포함한다.
