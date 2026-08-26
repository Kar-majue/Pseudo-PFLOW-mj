# 西尾市・小山市ケース実行ガイド

このドキュメントは、現在の package を他の担当者へ渡し、パラメータ調整を行ってもらうための手順書です。

今回の作業では、すでに作成済みの agent / activity ファイルを利用します。担当者は主に下記 3 つの Java ファイルのパラメータを調整し、trip / trajectory ファイルを再計算します。

## 1. 対象の Java ファイル

| ケース | main class | 入力 activity | 出力先 |
| --- | --- | --- | --- |
| 西尾市 taxi | `pseudo.gen.TripGenerator_WebAPI_refactor_taxi_nishio` | `nishioshi_case/person/activity/23/` | `nishioshi_case/person/trip_taxi/23/`, `nishioshi_case/person/trajectory_taxi/23/` |
| 小山市 taxi | `pseudo.gen.TripGenerator_WebAPI_refactor_taxi_oyama` | `oyama_case/person/activity/22/` | `oyama_case/person/trip_taxi/22/`, `oyama_case/person/trajectory_taxi/22/` |
| 小山市 on-demand GTFS | `pseudo.gen.TripGenerator_WebAPI_GTFS_oyama` | `oyama_case/ondemand_person/activity/22/` | `oyama_case/ondemand_person/trip/22/`, `oyama_case/ondemand_person/trajectory/22/` |

ローカル環境依存の絶対パスは、相対パスで動くように調整済みです。

- `src/main/resources/config.properties`
  - `root=./`
  - `inputDir=./data/`
- 3 つの Java ファイルの `outputDir` は `root` を基準に解決します。

実行時の working directory は、必ず project root、つまり `Pseudo-PFLOW/` にしてください。

## 2. 実行環境

推奨環境は以下です。

- JDK 11
- Maven 3.6.3 前後
- WebAPI `https://157.82.223.35/webapi/...` にアクセスできるネットワーク環境

Maven が `pflowlib` を見つけられない場合は、最初に以下を実行してください。

```bash
mvn install:install-file \
  -Dfile=lib/pflowlib.jar \
  -DgroupId=jp.ac.ut.csis \
  -DartifactId=pflowlib \
  -Dversion=1.0 \
  -Dpackaging=jar
```

ビルドは以下で実行します。

```bash
mvn -DskipTests package assembly:single
```

IntelliJ IDEA から直接実行しても構いません。その場合は以下を確認してください。

- Project SDK が JDK 11 であること
- `src/main/resources` が Resources として認識されていること
- Run Configuration の Working directory が project root であること

## 3. 設定ファイル

設定ファイルは以下です。

```text
src/main/resources/config.properties
```

確認項目は以下です。

- `root=./`
- `inputDir=./data/`
- `api.userID` と `api.password` に利用可能な WebAPI アカウントが設定されていること
- `api.createSessionURL`, `api.getRoadRouteURL`, `api.getMixedRouteURL` が利用可能な WebAPI を指していること
- `car.22`, `bike.22`, `car.23`, `bike.23` が設定されていること

この package では API アカウント情報を保持して渡す想定です。公開 repository へ push する場合のみ、認証情報の扱いに注意してください。

## 4. 必要なデータファイル

今回対象の 3 つの Java entry point は、S3 から直接データを読みません。実行時には local の `data/` 以下を参照します。

全ケース共通で必要なファイルは以下です。

```text
data/city_boundary.csv
data/base_station.csv
```

西尾市 taxi で必要なファイルは以下です。

```text
data/network/drm_23.tsv
data/network/railnetwork.tsv
nishioshi_case/person/activity/23/person_23213_labor.csv
nishioshi_case/person/activity/23/person_23213_nolabor.csv
nishioshi_case/person/activity/23/person_23213_student.csv
```

小山市 taxi で必要なファイルは以下です。

```text
data/network/drm_22.tsv
data/network/railnetwork.tsv
oyama_case/person/activity/22/person_22344_labor.csv
oyama_case/person/activity/22/person_22344_nolabor.csv
oyama_case/person/activity/22/person_22344_student.csv
```

小山市 on-demand GTFS で必要なファイルは以下です。

```text
data/network/drm_22.tsv
data/ondemand_gtfs/stops.txt
data/ondemand_gtfs/trips.txt
data/ondemand_gtfs/stop_times.txt
data/ondemand_gtfs/fare_attributes.txt
data/ondemand_gtfs/fare_rules.txt
oyama_case/ondemand_person/activity/22/person_22344_labor.csv
oyama_case/ondemand_person/activity/22/person_22344_nolabor.csv
oyama_case/ondemand_person/activity/22/person_22344_student.csv
```

GTFS は、今後の確認や feed 切り替えのため、`data/ondemand_gtfs/` 以下を folder ごと渡すことを推奨します。現在の folder には `agency.txt`, `calendar.txt`, `calendar_dates.txt`, `routes.txt`, `transfers.txt`, `feed_info.txt` なども含まれています。

README には `s3://pseudo-pflow/processing` から dataset を取得する説明がありますが、今回の trip / trajectory 計算では上記ファイルが local にあれば十分です。`data/markov/`, `data/mnl/`, `data/school/`, `data/input/` は主に activity 生成用であり、今回の 3 つの entry point では直接利用しません。

## 5. 実行方法

fat jar を作成した後、以下のように実行できます。

```bash
java -cp target/DSPFlow-0.0.1-SNAPSHOT-jar-with-dependencies.jar pseudo.gen.TripGenerator_WebAPI_refactor_taxi_nishio
java -cp target/DSPFlow-0.0.1-SNAPSHOT-jar-with-dependencies.jar pseudo.gen.TripGenerator_WebAPI_refactor_taxi_oyama
java -cp target/DSPFlow-0.0.1-SNAPSHOT-jar-with-dependencies.jar pseudo.gen.TripGenerator_WebAPI_GTFS_oyama
```

jar 名が異なる場合は、以下で確認してください。

```bash
ls target/*jar-with-dependencies.jar
```

IntelliJ IDEA から実行する場合は、1 章の main class を Run Configuration に指定してください。

実行時に `Session created successfully` が表示されれば、WebAPI の session 作成に成功しています。大量の error や停止が発生する場合は、まず WebAPI の接続、アカウント、activity の座標、`data/network` の有無を確認してください。

## 6. 出力ファイル

西尾市 taxi の出力は以下です。

```text
nishioshi_case/person/trip_taxi/23/trip_23213_labor.csv
nishioshi_case/person/trip_taxi/23/trip_23213_nolabor.csv
nishioshi_case/person/trip_taxi/23/trip_23213_student.csv
nishioshi_case/person/trajectory_taxi/23/trajectory_23213_labor.csv
nishioshi_case/person/trajectory_taxi/23/trajectory_23213_nolabor.csv
nishioshi_case/person/trajectory_taxi/23/trajectory_23213_student.csv
```

小山市 taxi の出力は以下です。

```text
oyama_case/person/trip_taxi/22/trip_22344_labor.csv
oyama_case/person/trip_taxi/22/trip_22344_nolabor.csv
oyama_case/person/trip_taxi/22/trip_22344_student.csv
oyama_case/person/trajectory_taxi/22/trajectory_22344_labor.csv
oyama_case/person/trajectory_taxi/22/trajectory_22344_nolabor.csv
oyama_case/person/trajectory_taxi/22/trajectory_22344_student.csv
```

小山市 on-demand GTFS の出力は以下です。

```text
oyama_case/ondemand_person/trip/22/trip_original_22344_labor.csv
oyama_case/ondemand_person/trip/22/trip_original_22344_nolabor.csv
oyama_case/ondemand_person/trip/22/trip_original_22344_student.csv
oyama_case/ondemand_person/trajectory/22/trajectory_original_22344_labor.csv
oyama_case/ondemand_person/trajectory/22/trajectory_original_22344_nolabor.csv
oyama_case/ondemand_person/trajectory/22/trajectory_original_22344_student.csv
oyama_case/ondemand_person/trip/22/station_count_original_22344_labor.csv
oyama_case/ondemand_person/trip/22/station_count_original_22344_nolabor.csv
oyama_case/ondemand_person/trip/22/station_count_original_22344_student.csv
```

同じ entry point を再実行すると、同名の出力ファイルは上書きされます。複数 experiment の結果を保存したい場合は、実行前に出力 folder を別名で退避してください。

## 7. 主なパラメータ調整箇所

### 7.1 共通の交通手段選択パラメータ

3 つの Java ファイルの上部付近に、以下の定数があります。

```java
MIN_TRANSIT_DISTANCE
FARE_PER_KILOMETER
FARE_PER_HOUR
FATIGUE_INDEX_WALK
FATIGUE_INDEX_BICYCLE
FARE_INIT
CAR_AVAILABILITY
```

これらは `determineTransportMode(...)` の交通手段選択 cost に利用されます。

- car cost = 初乗り相当 cost + 距離 cost + 時間価値 cost
- walk cost = 歩行時間 * 時間価値 * 疲労係数
- bicycle cost = 自転車時間 * 時間価値 * 疲労係数
- mix / public transit cost = WebAPI fare + WebAPI total time * 時間価値

`CAR_AVAILABILITY` を大きくすると、車を所有していない agent でも car が選択肢に入りやすくなります。`MIN_TRANSIT_DISTANCE` を大きくすると、短距離 trip では public transit / mixed route を問い合わせにくくなります。

### 7.2 西尾市 taxi パラメータ

対象ファイル：

```text
src/pseudo/gen/TripGenerator_WebAPI_refactor_taxi_nishio.java
```

主な調整箇所：

```java
TAXI_WAIT_SECOND = 5 * 60
TAXI_FARE_FIX = 300
```

現在の taxi cost は以下です。

```text
taxi cost = TAXI_FARE_FIX + travel time cost + wait time cost
```

固定運賃、補助額、待ち時間の感度分析に使いやすい形です。

### 7.3 小山市 taxi パラメータ

対象ファイル：

```text
src/pseudo/gen/TripGenerator_WebAPI_refactor_taxi_oyama.java
```

主な調整箇所：

```java
TAXI_WAIT_SECOND = 5 * 60
TAXI_BASE_DISTANCE = 1.2
TAXI_BASE_FARE = 660
TAXI_INCREMENT = 279
TAXI_INCREMENT_FARE = 90
TAXI_DISCOUNT = 0.8
```

現在の taxi cost は以下です。

```text
taxi fare = base fare + ceil(extra distance / increment) * increment fare
taxi cost = taxi fare * TAXI_DISCOUNT + travel time cost + wait time cost
```

初乗り距離、初乗り運賃、加算距離、加算運賃、相乗り割引、待ち時間を調整できます。

### 7.4 小山市 on-demand GTFS パラメータ

対象ファイル：

```text
src/pseudo/gen/TripGenerator_WebAPI_GTFS_oyama.java
```

主な調整箇所：

```java
FATIGUE_INDEX_WALK_SLOPE
boolean useRevised = false
feedFolder = "ondemand_gtfs"
gtfsLabel = "original"
```

現在は `useRevised` のどちらの分岐でも `data/ondemand_gtfs` を参照しています。複数 GTFS feed を比較したい場合は、以下のように feed folder を分けてください。

```text
data/ondemand_gtfs_original/
data/ondemand_gtfs_revised/
```

そのうえで `feedFolder` と `gtfsLabel` を変更すると、出力ファイル名で experiment を区別しやすくなります。

## 8. よくあるエラー

- `config.properties file not found in the classpath`
  - `src/main/resources` が resources として認識されているか確認してください。
- `FileNotFoundException: ./data/...`
  - 4 章の必要ファイルが存在するか、project root から実行しているか確認してください。
- `Failed to create session`
  - WebAPI の URL、アカウント、パスワード、ネットワーク接続を確認してください。
- `NullPointerException` が `actDir.listFiles()` 付近で発生する
  - 対応する case の activity folder が存在するか確認してください。
- 出力ファイル名が notebook と一致しない
  - 最新コードでは `person_*.csv` から `person_` と `.csv` を除いた suffix を使います。例えば `person_22344_student.csv` から `trip_22344_student.csv` が生成されます。

## 9. 推奨する受け渡しファイル

相手が Java のパラメータを調整して再実行する場合、最低限渡すべきファイル / folder は以下です。

```text
pom.xml
lib/pflowlib.jar
src/
src/main/resources/config.properties
data/city_boundary.csv
data/base_station.csv
data/network/drm_22.tsv
data/network/drm_23.tsv
data/network/railnetwork.tsv
data/ondemand_gtfs/
nishioshi_case/person/activity/23/
oyama_case/person/activity/22/
oyama_case/ondemand_person/activity/22/
nishio_oyama_case_guide.md
```

補足として、`src/` は依存 class が多いため folder ごと渡すのが安全です。対象の 3 ファイルだけを渡すと、local class 依存の解決が面倒になります。

すでに build 済みの jar を渡したい場合は、以下も追加できます。ただし、相手が Java ファイルを編集する場合は再 build が必要です。

```text
target/DSPFlow-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

## 10. 現在の package から除外できるファイル

今回の西尾市・小山市 trip / trajectory 再計算に不要なため、軽量化する場合は以下を除外できます。

```text
.git/
.github/
.agents/
.codex/
.history/
.vscode/
__pycache__/
download/
target/
META-INF/
docs/
src/scripts/
nanbus/
takehara_case/
data/markov/
data/mnl/
data/input/
data/school/
data/pre_labor_rate.csv
data/pre_holiday_rate.csv
data/pre_enrollment_rate.csv
data/city_census_od.csv
data/city_neighbors.tsv
data/city_hospital.csv
data/city_pre_school.csv
data/city_restaurant.csv
data/city_retail.csv
data/city_school.csv
data/city_tatemono.csv
data/mesh_ecensus.csv
data/act_transport.csv
data/input.json
data/network/drm_16.tsv
```

case folder 内の過去の計算結果も、調参実行そのものには不要です。比較用に残したい場合だけ渡してください。

```text
nishioshi_case/person/trip/
nishioshi_case/person/trajectory/
nishioshi_case/person/trip_taxi/
nishioshi_case/person/trajectory_taxi/
nishioshi_case/trip_analysis.ipynb
nishioshi_case/trip_23213.csv
oyama_case/person/trip_origin/
oyama_case/person/trajectory_origin/
oyama_case/person/trip_taxi/
oyama_case/person/trajectory_taxi/
oyama_case/ondemand_person/trip/
oyama_case/ondemand_person/trajectory/
oyama_case/trip_analysis.ipynb
oyama_case/person_22344.csv
```

ただし、以下の activity folder は削除しないでください。

```text
nishioshi_case/person/activity/23/
oyama_case/person/activity/22/
oyama_case/ondemand_person/activity/22/
```
