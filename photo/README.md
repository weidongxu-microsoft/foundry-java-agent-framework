# raw-photo

RAW → JPEG develop core. Standalone workload library (independent Maven build, Java 17); `app`
depends on it as a binary artifact. Design + rationale: `plan/19-raw-photo-tool.md`.

No pure-Java RAW decoder handles modern cameras, so imaging is done by the native
`rawtherapee-cli`; this lib only maps params → a `.pp3` profile and drives the process.

## API

- `DevelopSettings` — editor-neutral params (WB temp, tint, exposure EV, contrast, saturation,
  highlight/shadow recovery, tone curve). `neutral()` = baseline; `fromJson(...)` parses the vision
  step's JSON.
- `Pp3Writer.toPp3(settings)` — settings → RawTherapee 5.12 `pp3` (null when neutral).
- `RawDeveloper` / `RawTherapeeDeveloper(RawTherapeeOptions)` — `develop(raw, settings, outJpeg)`.

```java
RawDeveloper dev = new RawTherapeeDeveloper();               // rawtherapee-cli from PATH / $RAWTHERAPEE_CLI
dev.develop(raw, DevelopSettings.neutral(), baseline);       // baseline
dev.develop(raw, DevelopSettings.fromJson(advice, mapper), adjusted); // adjusted
```

## Build & test

```powershell
mvn -f photo/pom.xml install         # build + unit tests (Pp3WriterTest)
```

`RawTherapeeDeveloperIT` is an end-to-end develop, skipped unless both env vars are set:

```powershell
$env:RAWTHERAPEE_CLI="C:\Program Files\RawTherapee\5.12\rawtherapee-cli.exe"
$env:RAW_SAMPLE="C:\path\to\sample.RAF"
mvn -f photo/pom.xml "-Dtest=RawTherapeeDeveloperIT" test
```

Runtime needs `rawtherapee-cli` installed (Debian: `apt-get install rawtherapee`).
