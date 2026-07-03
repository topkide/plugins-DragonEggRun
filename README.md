# DragonEggRun — 드래곤알 들고 도망가기

Paper 1.21.8 미니게임 플러그인. 머리 위 드래곤알(ItemDisplay)을 들고 도망치고, 우클릭으로 강탈하며, 밤에는 발자국이 남는다.

## 빌드

BossSpawner와 동일한 방식(Gradle Wrapper 8.8 + Shadow).

```bash
./gradlew build
```

산출물: `build/libs/DragonEggRun-1.0.0-all.jar`
(외부 의존성이 없으므로 `DragonEggRun-1.0.0.jar` 도 그대로 사용 가능)

- Java 21 toolchain
- `group=com.dotorimaru`, `version=1.0.0` (gradle.properties)
- 메인 클래스: `com.dotorimaru.dragoneggrun.DragonEggRunPlugin`
- `plugin.yml` 은 processResources 에서 `${version}` 등 치환

## 명령어 (`/드래곤알`, alias: `드래곤`, `eggrun`)

| 명령어 | 설명 |
|---|---|
| `/드래곤알 모집` | 참가 모집 시작 |
| `/드래곤알 참가 [닉]` | 게임 참가 |
| `/드래곤알 퇴장 [닉]` | 게임 퇴장 |
| `/드래곤알 인원` | 참가자 목록 |
| `/드래곤알 모드 <랜덤\|맵>` | 알 지급 방식 |
| `/드래곤알 위치설정` | (맵 모드) 알 생성 위치 저장 |
| `/드래곤알 준비시간 <초>` | 흩어지는 준비 시간 설정 (config 저장) |
| `/드래곤알 진행시간 <초>` | 게임 진행 시간 설정 (config 저장) |
| `/드래곤알 시작` | 준비시간 후 게임 시작 |
| `/드래곤알 중지` | 게임 종료 |
| `/드래곤알 새로고침` | config 리로드 |

## 진행 흐름

1. (선택) `/드래곤알 모드 맵` → `/드래곤알 위치설정`
2. `/드래곤알 모집` → 참가자들이 `/드래곤알 참가`
3. `/드래곤알 시작` → `prep-seconds` 동안 흩어짐 → 랜덤 지급 또는 맵에 알 등장 → `game-seconds` 진행 (남은 시간 보스바 표시)
4. **알 소지자는 투명** 상태이며, `reveal-radius`(기본 5블록) 안에 든 상대에게만 보임 (per-viewer)
5. 소지자가 `camp-seconds`(기본 3초) 이상 제자리에 있으면 **투명 해제 + 발광**(전원에게 노출)
6. 소지자 머리 위 알을 **우클릭하면 강탈** → 뺏긴 사람 `freeze-seconds` 빙결 + 공지
7. 소지자 사망/접속종료 시 알이 블록으로 떨어짐 → 다시 우클릭으로 줍기
8. **진행 시간이 끝나면 그때 알을 든 사람이 승리** (아무도 안 들고 있으면 무승부)

발자국은 기본적으로 시간대와 무관하게 항상 남습니다 (`footprint.night-only: false`).

## 설정 (config.yml)

지급 방식, 준비/진행/빙결/보호 시간, 투명 노출 거리(`stealth.reveal-radius`)·캠핑 시간(`stealth.camp-seconds`),
머리 위 알 크기·높이, 발자국 색 팔레트(참가 순서대로 배정)·표시 시간대(`night-only`), 전체 메시지 커스터마이즈.
