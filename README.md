# took-parser

한국어 자연어 시간 표현을 파싱하는 Kotlin Multiplatform 라이브러리입니다.

## 지원 타겟

- Android
- JVM
- iOS (iosX64, iosArm64, iosSimulatorArm64)

## 설치 (JitPack)

`settings.gradle.kts`에 JitPack 저장소 추가:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

`build.gradle.kts`에 의존성 추가:

```kotlin
dependencies {
    implementation("com.github.<your-username>:took-parser:<version>")
}
```

## 사용법

### TimeParser — 기본 시간 파싱

```kotlin
val parser = TimeParser()

// Scheduled: 파싱 성공
val result = parser.parse("내일 오후 3시 치과")
if (result is TimeParseResult.Scheduled) {
    println(result.title)       // "치과"
    println(result.scheduledAt) // epoch millis
}

// Buffered: 시간 표현 없음
parser.parse("뚜띠 간식") // TimeParseResult.Buffered

// NeedsFallback: 모호한 표현 (LLM 폴백 필요)
parser.parse("퇴근 후 마트") // TimeParseResult.NeedsFallback
```

### 생활 구간 해석 (LifeSegmentResolver)

```kotlin
val schedule = LifeSchedule(
    isConfigured = true,
    workStartHour = 10, workStartMinute = 0,
    workEndHour = 19, workEndMinute = 0,
    commuteMinutes = 45,
    lunchHour = 12, lunchMinute = 0,
    dinnerHour = 19, dinnerMinute = 30,
    wakeUpHour = 7, wakeUpMinute = 0,
    bedtimeHour = 23, bedtimeMinute = 30,
)
val resolver = LifeSegmentResolver(schedule, isPremium = true)

val result = TimeParser().parse("퇴근하고 운동", resolver)
// TimeParseResult.Scheduled, scheduledAt = workEndTime + commuteMinutes
```

### 캘린더 컨텍스트 해석 (CalendarContextResolver)

```kotlin
val calResolver = CalendarContextResolver(
    eventFinder = { keywords, hoursAhead ->
        // 캘린더에서 이벤트 조회
        myCalendarApi.findEvent(keywords, hoursAhead)
    }
)

val result = TimeParser().parse("회의 전에 자료 준비", calendarResolver = calResolver)
// TimeParseResult.Scheduled, scheduledAt = eventStartMillis - 30분
```

### 반복 일정

```kotlin
val result = parser.parse("매일 아침 7시 스트레칭")
if (result is TimeParseResult.Scheduled) {
    println(result.recurrenceRule?.type) // RecurrenceType.DAILY
    println(result.recurrenceRule?.hour) // 7
}
```

## 지원 표현 예시

| 입력 | 결과 |
|------|------|
| `7시 다이소 그릇` | Scheduled, 19:00 |
| `내일 오전 9시 약` | Scheduled, 다음날 09:00 |
| `금요일까지 PR 올리기` | Scheduled, 이번 주 금요일 |
| `30분 뒤 세탁기` | Scheduled, 현재+30분 |
| `매주 금요일 보고서` | Scheduled + RecurrenceRule(WEEKLY, 금) |
| `회의 전에 자료 준비` | CalendarContextResolver 통해 Scheduled |
| `퇴근하고 마트` | LifeSegmentResolver 통해 Scheduled |
| `뚜띠 간식` | Buffered |
| `퇴근 후 마트` | NeedsFallback |

## 빌드

```bash
./gradlew :parser:jvmTest          # JVM 테스트
./gradlew :parser:check            # 전체 검증
```

## 기술 스택

- Kotlin 2.0.21
- kotlinx-datetime 0.6.1
- Kotlin Multiplatform (KMP)
