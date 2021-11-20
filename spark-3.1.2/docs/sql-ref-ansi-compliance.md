---
layout: global
title: ANSI Compliance
displayTitle: ANSI Compliance
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
 
     http://www.apache.org/licenses/LICENSE-2.0
 
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Since Spark 3.0, Spark SQL introduces two experimental options to comply with the SQL standard: `spark.sql.ansi.enabled` and `spark.sql.storeAssignmentPolicy` (See a table below for details).

When `spark.sql.ansi.enabled` is set to `true`, Spark SQL uses an ANSI compliant dialect instead of being Hive compliant. For example, Spark will throw an exception at runtime instead of returning null results if the inputs to a SQL operator/function are invalid. Some ANSI dialect features may be not from the ANSI SQL standard directly, but their behaviors align with ANSI SQL's style.

Moreover, Spark SQL has an independent option to control implicit casting behaviours when inserting rows in a table.
The casting behaviours are defined as store assignment rules in the standard.

When `spark.sql.storeAssignmentPolicy` is set to `ANSI`, Spark SQL complies with the ANSI store assignment rules. This is a separate configuration because its default value is `ANSI`, while the configuration `spark.sql.ansi.enabled` is disabled by default.

|Property Name|Default|Meaning|Since Version|
|-------------|-------|-------|-------------|
|`spark.sql.ansi.enabled`|false|(Experimental) When true, Spark tries to conform to the ANSI SQL specification: <br/> 1. Spark will throw a runtime exception if an overflow occurs in any operation on integral/decimal field. <br/> 2. Spark will forbid using the reserved keywords of ANSI SQL as identifiers in the SQL parser.|3.0.0|
|`spark.sql.storeAssignmentPolicy`|ANSI|(Experimental) When inserting a value into a column with different data type, Spark will perform type coercion.  Currently, we support 3 policies for the type coercion rules: ANSI, legacy and strict. With ANSI policy, Spark performs the type coercion as per ANSI SQL. In practice, the behavior is mostly the same as PostgreSQL.  It disallows certain unreasonable type conversions such as converting string to int or double to boolean.  With legacy policy, Spark allows the type coercion as long as it is a valid Cast, which is very loose.  e.g. converting string to int or double to boolean is allowed.  It is also the only behavior in Spark 2.x and it is compatible with Hive.  With strict policy, Spark doesn't allow any possible precision loss or data truncation in type coercion, e.g. converting double to int or decimal to double is not allowed.|3.0.0|

The following subsections present behaviour changes in arithmetic operations, type conversions, and SQL parsing when the ANSI mode enabled.

### Arithmetic Operations

In Spark SQL, arithmetic operations performed on numeric types (with the exception of decimal) are not checked for overflows by default.
This means that in case an operation causes overflows, the result is the same with the corresponding operation in a Java/Scala program (e.g., if the sum of 2 integers is higher than the maximum value representable, the result is a negative number).
On the other hand, Spark SQL returns null for decimal overflows.
When `spark.sql.ansi.enabled` is set to `true` and an overflow occurs in numeric and interval arithmetic operations, it throws an arithmetic exception at runtime.

```sql
-- `spark.sql.ansi.enabled=true`
SELECT 2147483647 + 1;
java.lang.ArithmeticException: integer overflow

-- `spark.sql.ansi.enabled=false`
SELECT 2147483647 + 1;
+----------------+
|(2147483647 + 1)|
+----------------+
|     -2147483648|
+----------------+
```

### Type Conversion

Spark SQL has three kinds of type conversions: explicit casting, type coercion, and store assignment casting.
When `spark.sql.ansi.enabled` is set to `true`, explicit casting by `CAST` syntax throws a runtime exception for illegal cast patterns defined in the standard, e.g. casts from a string to an integer.
On the other hand, `INSERT INTO` syntax throws an analysis exception when the ANSI mode enabled via `spark.sql.storeAssignmentPolicy=ANSI`.

The type conversion of Spark ANSI mode follows the syntax rules of section 6.13 "cast specification" in [ISO/IEC 9075-2:2011 Information technology — Database languages - SQL — Part 2: Foundation (SQL/Foundation)](https://www.iso.org/standard/53682.html), except it specially allows the following
 straightforward type conversions which are disallowed as per the ANSI standard:
* NumericType <=> BooleanType
* StringType <=> BinaryType

 The valid combinations of target data type and source data type in a `CAST` expression are given by the following table.
“Y” indicates that the combination is syntactically valid without restriction and “N” indicates that the combination is not valid.

| Source\Target | Numeric | String | Date | Timestamp | Interval | Boolean | Binary | Array | Map | Struct |
|-----------|---------|--------|------|-----------|----------|---------|--------|-------|-----|--------|
| Numeric   | Y       | Y      | N    | N         | N        | Y       | N      | N     | N   | N      |
| String    | Y       | Y      | Y    | Y         | Y        | Y       | Y      | N     | N   | N      |
| Date      | N       | Y      | Y    | Y         | N        | N       | N      | N     | N   | N      |
| Timestamp | N       | Y      | Y    | Y         | N        | N       | N      | N     | N   | N      |
| Interval  | N       | Y      | N    | N         | Y        | N       | N      | N     | N   | N      |
| Boolean   | Y       | Y      | N    | N         | N        | Y       | N      | N     | N   | N      |
| Binary    | Y       | N      | N    | N         | N        | N       | Y      | N     | N   | N      |
| Array     | N       | N      | N    | N         | N        | N       | N      | Y     | N   | N      |
| Map       | N       | N      | N    | N         | N        | N       | N      | N     | Y   | N      |
| Struct    | N       | N      | N    | N         | N        | N       | N      | N     | N   | Y      |

Currently, the ANSI mode affects explicit casting and assignment casting only.
In future releases, the behaviour of type coercion might change along with the other two type conversion rules.

```sql
-- Examples of explicit casting

-- `spark.sql.ansi.enabled=true`
SELECT CAST('a' AS INT);
java.lang.NumberFormatException: invalid input syntax for type numeric: a

SELECT CAST(2147483648L AS INT);
java.lang.ArithmeticException: Casting 2147483648 to int causes overflow

SELECT CAST(DATE'2020-01-01' AS INT)
org.apache.spark.sql.AnalysisException: cannot resolve 'CAST(DATE '2020-01-01' AS INT)' due to data type mismatch: cannot cast date to int.
To convert values from date to int, you can use function UNIX_DATE instead.

-- `spark.sql.ansi.enabled=false` (This is a default behaviour)
SELECT CAST('a' AS INT);
+--------------+
|CAST(a AS INT)|
+--------------+
|          null|
+--------------+

SELECT CAST(2147483648L AS INT);
+-----------------------+
|CAST(2147483648 AS INT)|
+-----------------------+
|            -2147483648|
+-----------------------+

SELECT CAST(DATE'2020-01-01' AS INT)
+------------------------------+
|CAST(DATE '2020-01-01' AS INT)|
+------------------------------+
|                          null|
+------------------------------+

-- Examples of store assignment rules
CREATE TABLE t (v INT);

-- `spark.sql.storeAssignmentPolicy=ANSI`
INSERT INTO t VALUES ('1');
org.apache.spark.sql.AnalysisException: Cannot write incompatible data to table '`default`.`t`':
- Cannot safely cast 'v': string to int;

-- `spark.sql.storeAssignmentPolicy=LEGACY` (This is a legacy behaviour until Spark 2.x)
INSERT INTO t VALUES ('1');
SELECT * FROM t;
+---+
|  v|
+---+
|  1|
+---+
```

### SQL Functions

The behavior of some SQL functions can be different under ANSI mode (`spark.sql.ansi.enabled=true`).
  - `size`: This function returns null for null input.
  - `element_at`:
    - This function throws `ArrayIndexOutOfBoundsException` if using invalid indices.
    - This function throws `NoSuchElementException` if key does not exist in map.
  - `elt`: This function throws `ArrayIndexOutOfBoundsException` if using invalid indices.
  - `parse_url`: This function throws `IllegalArgumentException` if an input string is not a valid url.
  - `to_date`: This function should fail with an exception if the input string can't be parsed, or the pattern string is invalid.
  - `to_timestamp`: This function should fail with an exception if the input string can't be parsed, or the pattern string is invalid.
  - `unix_timestamp`: This function should fail with an exception if the input string can't be parsed, or the pattern string is invalid.
  - `to_unix_timestamp`: This function should fail with an exception if the input string can't be parsed, or the pattern string is invalid.
  - `make_date`: This function should fail with an exception if the result date is invalid.
  - `make_timestamp`: This function should fail with an exception if the result timestamp is invalid.
  - `make_interval`:  This function should fail with an exception if the result interval is invalid.

### SQL Operators

The behavior of some SQL operators can be different under ANSI mode (`spark.sql.ansi.enabled=true`).
  - `array_col[index]`: This operator throws `ArrayIndexOutOfBoundsException` if using invalid indices.
  - `map_col[key]`: This operator throws `NoSuchElementException` if key does not exist in map.
  - `CAST(string_col AS TIMESTAMP)`: This operator should fail with an exception if the input string can't be parsed.

### SQL Keywords

When `spark.sql.ansi.enabled` is true, Spark SQL will use the ANSI mode parser.
In this mode, Spark SQL has two kinds of keywords:
* Reserved keywords: Keywords that are reserved and can't be used as identifiers for table, view, column, function, alias, etc.
* Non-reserved keywords: Keywords that have a special meaning only in particular contexts and can be used as identifiers in other contexts. For example, `EXPLAIN SELECT ...` is a command, but EXPLAIN can be used as identifiers in other places.

When the ANSI mode is disabled, Spark SQL has two kinds of keywords:
* Non-reserved keywords: Same definition as the one when the ANSI mode enabled.
* Strict-non-reserved keywords: A strict version of non-reserved keywords, which can not be used as table alias.

By default `spark.sql.ansi.enabled` is false.

Below is a list of all the keywords in Spark SQL.

|Keyword|Spark SQL<br/>ANSI Mode|Spark SQL<br/>Default Mode|SQL-2016|
|-------|----------------------|-------------------------|--------|
|ADD|non-reserved|non-reserved|non-reserved|
|AFTER|non-reserved|non-reserved|non-reserved|
|ALL|reserved|non-reserved|reserved|
|ALTER|non-reserved|non-reserved|reserved|
|ANALYZE|non-reserved|non-reserved|non-reserved|
|AND|reserved|non-reserved|reserved|
|ANTI|non-reserved|strict-non-reserved|non-reserved|
|ANY|reserved|non-reserved|reserved|
|ARCHIVE|non-reserved|non-reserved|non-reserved|
|ARRAY|non-reserved|non-reserved|reserved|
|AS|reserved|non-reserved|reserved|
|ASC|non-reserved|non-reserved|non-reserved|
|AT|non-reserved|non-reserved|reserved|
|AUTHORIZATION|reserved|non-reserved|reserved|
|BETWEEN|non-reserved|non-reserved|reserved|
|BOTH|reserved|non-reserved|reserved|
|BUCKET|non-reserved|non-reserved|non-reserved|
|BUCKETS|non-reserved|non-reserved|non-reserved|
|BY|non-reserved|non-reserved|reserved|
|CACHE|non-reserved|non-reserved|non-reserved|
|CASCADE|non-reserved|non-reserved|non-reserved|
|CASE|reserved|non-reserved|reserved|
|CAST|reserved|non-reserved|reserved|
|CHANGE|non-reserved|non-reserved|non-reserved|
|CHECK|reserved|non-reserved|reserved|
|CLEAR|non-reserved|non-reserved|non-reserved|
|CLUSTER|non-reserved|non-reserved|non-reserved|
|CLUSTERED|non-reserved|non-reserved|non-reserved|
|CODEGEN|non-reserved|non-reserved|non-reserved|
|COLLATE|reserved|non-reserved|reserved|
|COLLECTION|non-reserved|non-reserved|non-reserved|
|COLUMN|reserved|non-reserved|reserved|
|COLUMNS|non-reserved|non-reserved|non-reserved|
|COMMENT|non-reserved|non-reserved|non-reserved|
|COMMIT|non-reserved|non-reserved|reserved|
|COMPACT|non-reserved|non-reserved|non-reserved|
|COMPACTIONS|non-reserved|non-reserved|non-reserved|
|COMPUTE|non-reserved|non-reserved|non-reserved|
|CONCATENATE|non-reserved|non-reserved|non-reserved|
|CONSTRAINT|reserved|non-reserved|reserved|
|COST|non-reserved|non-reserved|non-reserved|
|CREATE|reserved|non-reserved|reserved|
|CROSS|reserved|strict-non-reserved|reserved|
|CUBE|non-reserved|non-reserved|reserved|
|CURRENT|non-reserved|non-reserved|reserved|
|CURRENT_DATE|reserved|non-reserved|reserved|
|CURRENT_TIME|reserved|non-reserved|reserved|
|CURRENT_TIMESTAMP|reserved|non-reserved|reserved|
|CURRENT_USER|reserved|non-reserved|reserved|
|DATA|non-reserved|non-reserved|non-reserved|
|DATABASE|non-reserved|non-reserved|non-reserved|
|DATABASES|non-reserved|non-reserved|non-reserved|
|DBPROPERTIES|non-reserved|non-reserved|non-reserved|
|DEFINED|non-reserved|non-reserved|non-reserved|
|DELETE|non-reserved|non-reserved|reserved|
|DELIMITED|non-reserved|non-reserved|non-reserved|
|DESC|non-reserved|non-reserved|non-reserved|
|DESCRIBE|non-reserved|non-reserved|reserved|
|DFS|non-reserved|non-reserved|non-reserved|
|DIRECTORIES|non-reserved|non-reserved|non-reserved|
|DIRECTORY|non-reserved|non-reserved|non-reserved|
|DISTINCT|reserved|non-reserved|reserved|
|DISTRIBUTE|non-reserved|non-reserved|non-reserved|
|DIV|non-reserved|non-reserved|not a keyword|
|DROP|non-reserved|non-reserved|reserved|
|ELSE|reserved|non-reserved|reserved|
|END|reserved|non-reserved|reserved|
|ESCAPE|reserved|non-reserved|reserved|
|ESCAPED|non-reserved|non-reserved|non-reserved|
|EXCEPT|reserved|strict-non-reserved|reserved|
|EXCHANGE|non-reserved|non-reserved|non-reserved|
|EXISTS|non-reserved|non-reserved|reserved|
|EXPLAIN|non-reserved|non-reserved|non-reserved|
|EXPORT|non-reserved|non-reserved|non-reserved|
|EXTENDED|non-reserved|non-reserved|non-reserved|
|EXTERNAL|non-reserved|non-reserved|reserved|
|EXTRACT|non-reserved|non-reserved|reserved|
|FALSE|reserved|non-reserved|reserved|
|FETCH|reserved|non-reserved|reserved|
|FIELDS|non-reserved|non-reserved|non-reserved|
|FILTER|reserved|non-reserved|reserved|
|FILEFORMAT|non-reserved|non-reserved|non-reserved|
|FIRST|non-reserved|non-reserved|non-reserved|
|FOLLOWING|non-reserved|non-reserved|non-reserved|
|FOR|reserved|non-reserved|reserved|
|FOREIGN|reserved|non-reserved|reserved|
|FORMAT|non-reserved|non-reserved|non-reserved|
|FORMATTED|non-reserved|non-reserved|non-reserved|
|FROM|reserved|non-reserved|reserved|
|FULL|reserved|strict-non-reserved|reserved|
|FUNCTION|non-reserved|non-reserved|reserved|
|FUNCTIONS|non-reserved|non-reserved|non-reserved|
|GLOBAL|non-reserved|non-reserved|reserved|
|GRANT|reserved|non-reserved|reserved|
|GROUP|reserved|non-reserved|reserved|
|GROUPING|non-reserved|non-reserved|reserved|
|HAVING|reserved|non-reserved|reserved|
|IF|non-reserved|non-reserved|not a keyword|
|IGNORE|non-reserved|non-reserved|non-reserved|
|IMPORT|non-reserved|non-reserved|non-reserved|
|IN|reserved|non-reserved|reserved|
|INDEX|non-reserved|non-reserved|non-reserved|
|INDEXES|non-reserved|non-reserved|non-reserved|
|INNER|reserved|strict-non-reserved|reserved|
|INPATH|non-reserved|non-reserved|non-reserved|
|INPUTFORMAT|non-reserved|non-reserved|non-reserved|
|INSERT|non-reserved|non-reserved|reserved|
|INTERSECT|reserved|strict-non-reserved|reserved|
|INTERVAL|non-reserved|non-reserved|reserved|
|INTO|reserved|non-reserved|reserved|
|IS|reserved|non-reserved|reserved|
|ITEMS|non-reserved|non-reserved|non-reserved|
|JOIN|reserved|strict-non-reserved|reserved|
|KEYS|non-reserved|non-reserved|non-reserved|
|LAST|non-reserved|non-reserved|non-reserved|
|LATERAL|non-reserved|non-reserved|reserved|
|LAZY|non-reserved|non-reserved|non-reserved|
|LEADING|reserved|non-reserved|reserved|
|LEFT|reserved|strict-non-reserved|reserved|
|LIKE|non-reserved|non-reserved|reserved|
|LIMIT|non-reserved|non-reserved|non-reserved|
|LINES|non-reserved|non-reserved|non-reserved|
|LIST|non-reserved|non-reserved|non-reserved|
|LOAD|non-reserved|non-reserved|non-reserved|
|LOCAL|non-reserved|non-reserved|reserved|
|LOCATION|non-reserved|non-reserved|non-reserved|
|LOCK|non-reserved|non-reserved|non-reserved|
|LOCKS|non-reserved|non-reserved|non-reserved|
|LOGICAL|non-reserved|non-reserved|non-reserved|
|MACRO|non-reserved|non-reserved|non-reserved|
|MAP|non-reserved|non-reserved|non-reserved|
|MATCHED|non-reserved|non-reserved|non-reserved|
|MERGE|non-reserved|non-reserved|non-reserved|
|MINUS|non-reserved|strict-non-reserved|non-reserved|
|MSCK|non-reserved|non-reserved|non-reserved|
|NAMESPACE|non-reserved|non-reserved|non-reserved|
|NAMESPACES|non-reserved|non-reserved|non-reserved|
|NATURAL|reserved|strict-non-reserved|reserved|
|NO|non-reserved|non-reserved|reserved|
|NOT|reserved|non-reserved|reserved|
|NULL|reserved|non-reserved|reserved|
|NULLS|non-reserved|non-reserved|non-reserved|
|OF|non-reserved|non-reserved|reserved|
|ON|reserved|strict-non-reserved|reserved|
|ONLY|reserved|non-reserved|reserved|
|OPTION|non-reserved|non-reserved|non-reserved|
|OPTIONS|non-reserved|non-reserved|non-reserved|
|OR|reserved|non-reserved|reserved|
|ORDER|reserved|non-reserved|reserved|
|OUT|non-reserved|non-reserved|reserved|
|OUTER|reserved|non-reserved|reserved|
|OUTPUTFORMAT|non-reserved|non-reserved|non-reserved|
|OVER|non-reserved|non-reserved|non-reserved|
|OVERLAPS|reserved|non-reserved|reserved|
|OVERLAY|non-reserved|non-reserved|non-reserved|
|OVERWRITE|non-reserved|non-reserved|non-reserved|
|PARTITION|non-reserved|non-reserved|reserved|
|PARTITIONED|non-reserved|non-reserved|non-reserved|
|PARTITIONS|non-reserved|non-reserved|non-reserved|
|PERCENT|non-reserved|non-reserved|non-reserved|
|PIVOT|non-reserved|non-reserved|non-reserved|
|PLACING|non-reserved|non-reserved|non-reserved|
|POSITION|non-reserved|non-reserved|reserved|
|PRECEDING|non-reserved|non-reserved|non-reserved|
|PRIMARY|reserved|non-reserved|reserved|
|PRINCIPALS|non-reserved|non-reserved|non-reserved|
|PROPERTIES|non-reserved|non-reserved|non-reserved|
|PURGE|non-reserved|non-reserved|non-reserved|
|QUERY|non-reserved|non-reserved|non-reserved|
|RANGE|non-reserved|non-reserved|reserved|
|RECORDREADER|non-reserved|non-reserved|non-reserved|
|RECORDWRITER|non-reserved|non-reserved|non-reserved|
|RECOVER|non-reserved|non-reserved|non-reserved|
|REDUCE|non-reserved|non-reserved|non-reserved|
|REFERENCES|reserved|non-reserved|reserved|
|REFRESH|non-reserved|non-reserved|non-reserved|
|REGEXP|non-reserved|non-reserved|not a keyword|
|RENAME|non-reserved|non-reserved|non-reserved|
|REPAIR|non-reserved|non-reserved|non-reserved|
|REPLACE|non-reserved|non-reserved|non-reserved|
|RESET|non-reserved|non-reserved|non-reserved|
|RESTRICT|non-reserved|non-reserved|non-reserved|
|REVOKE|non-reserved|non-reserved|reserved|
|RIGHT|reserved|strict-non-reserved|reserved|
|RLIKE|non-reserved|non-reserved|non-reserved|
|ROLE|non-reserved|non-reserved|non-reserved|
|ROLES|non-reserved|non-reserved|non-reserved|
|ROLLBACK|non-reserved|non-reserved|reserved|
|ROLLUP|non-reserved|non-reserved|reserved|
|ROW|non-reserved|non-reserved|reserved|
|ROWS|non-reserved|non-reserved|reserved|
|SCHEMA|non-reserved|non-reserved|non-reserved|
|SCHEMAS|non-reserved|non-reserved|not a keyword|
|SELECT|reserved|non-reserved|reserved|
|SEMI|non-reserved|strict-non-reserved|non-reserved|
|SEPARATED|non-reserved|non-reserved|non-reserved|
|SERDE|non-reserved|non-reserved|non-reserved|
|SERDEPROPERTIES|non-reserved|non-reserved|non-reserved|
|SESSION_USER|reserved|non-reserved|reserved|
|SET|non-reserved|non-reserved|reserved|
|SETS|non-reserved|non-reserved|non-reserved|
|SHOW|non-reserved|non-reserved|non-reserved|
|SKEWED|non-reserved|non-reserved|non-reserved|
|SOME|reserved|non-reserved|reserved|
|SORT|non-reserved|non-reserved|non-reserved|
|SORTED|non-reserved|non-reserved|non-reserved|
|START|non-reserved|non-reserved|reserved|
|STATISTICS|non-reserved|non-reserved|non-reserved|
|STORED|non-reserved|non-reserved|non-reserved|
|STRATIFY|non-reserved|non-reserved|non-reserved|
|STRUCT|non-reserved|non-reserved|non-reserved|
|SUBSTR|non-reserved|non-reserved|non-reserved|
|SUBSTRING|non-reserved|non-reserved|non-reserved|
|TABLE|reserved|non-reserved|reserved|
|TABLES|non-reserved|non-reserved|non-reserved|
|TABLESAMPLE|non-reserved|non-reserved|reserved|
|TBLPROPERTIES|non-reserved|non-reserved|non-reserved|
|TEMP|non-reserved|non-reserved|not a keyword|
|TEMPORARY|non-reserved|non-reserved|non-reserved|
|TERMINATED|non-reserved|non-reserved|non-reserved|
|THEN|reserved|non-reserved|reserved|
|TIME|reserved|non-reserved|reserved|
|TO|reserved|non-reserved|reserved|
|TOUCH|non-reserved|non-reserved|non-reserved|
|TRAILING|reserved|non-reserved|reserved|
|TRANSACTION|non-reserved|non-reserved|non-reserved|
|TRANSACTIONS|non-reserved|non-reserved|non-reserved|
|TRANSFORM|non-reserved|non-reserved|non-reserved|
|TRIM|non-reserved|non-reserved|non-reserved|
|TRUE|non-reserved|non-reserved|reserved|
|TRUNCATE|non-reserved|non-reserved|reserved|
|TYPE|non-reserved|non-reserved|non-reserved|
|UNARCHIVE|non-reserved|non-reserved|non-reserved|
|UNBOUNDED|non-reserved|non-reserved|non-reserved|
|UNCACHE|non-reserved|non-reserved|non-reserved|
|UNION|reserved|strict-non-reserved|reserved|
|UNIQUE|reserved|non-reserved|reserved|
|UNKNOWN|reserved|non-reserved|reserved|
|UNLOCK|non-reserved|non-reserved|non-reserved|
|UNSET|non-reserved|non-reserved|non-reserved|
|UPDATE|non-reserved|non-reserved|reserved|
|USE|non-reserved|non-reserved|non-reserved|
|USER|reserved|non-reserved|reserved|
|USING|reserved|strict-non-reserved|reserved|
|VALUES|non-reserved|non-reserved|reserved|
|VIEW|non-reserved|non-reserved|non-reserved|
|VIEWS|non-reserved|non-reserved|non-reserved|
|WHEN|reserved|non-reserved|reserved|
|WHERE|reserved|non-reserved|reserved|
|WINDOW|non-reserved|non-reserved|reserved|
|WITH|reserved|non-reserved|reserved|
|ZONE|non-reserved|non-reserved|non-reserved|
