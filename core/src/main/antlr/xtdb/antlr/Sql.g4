parser grammar Sql;

options {
  language = Java;
  caseInsensitive = true;
  tokenVocab = SqlLexer;
}

/// §22 Direct invocation of SQL

/// §22.1 <direct SQL statement>

directSqlStatement : directlyExecutableStatement ';'? EOF ;

directlyExecutableStatement
    : settingDefaultTimePeriod? queryExpression #QueryExpr
    | insertStatement #InsertStmt
    | updateStatementSearched #UpdateStmt
    | deleteStatementSearched #DeleteStmt
    | eraseStatementSearched #EraseStmt
    | 'ASSERT' searchCondition #AssertStatement
    | ('START' 'TRANSACTION' | 'BEGIN') transactionCharacteristics? # StartTransactionStatement
    | 'SET' 'LOCAL'? 'TRANSACTION' transactionCharacteristics # SetTransactionStatement
    | 'COMMIT' # CommitStatement
    | 'ROLLBACK' # RollbackStatement
    | 'SET' 'SESSION' 'CHARACTERISTICS' 'AS' sessionCharacteristic (',' sessionCharacteristic)* # SetSessionCharacteristicsStatement
    | 'SET' 'TIME' 'ZONE' characterString # SetTimeZoneStatement
    | 'SET' identifier ( 'TO' | '=' ) literal #SetSessionVariableStatement
    | 'SHOW' showVariable # ShowVariableStatement
    | 'SHOW' 'LATEST' 'SUBMITTED' 'TRANSACTION' # ShowLatestSubmittedTransactionStatement
    ;

showVariable
   : 'TRANSACTION' 'ISOLATION' 'LEVEL' # ShowTransactionIsolationLevel
   | 'STANDARD_CONFORMING_STRINGS' # ShowStandardConformingStrings
   | ('TIME' 'ZONE' | 'TIMEZONE') # ShowTimeZone
   ;

settingDefaultTimePeriod : 'SETTING'
   ( (defaultValidTimePeriod (',' defaultSystemTimePeriod)?)
   | (defaultSystemTimePeriod (',' defaultValidTimePeriod)?)
   )
   ;

defaultValidTimePeriod : 'DEFAULT' 'VALID_TIME' 'TO'? tableTimePeriodSpecification ;
defaultSystemTimePeriod : 'DEFAULT' 'SYSTEM_TIME' 'TO'? tableTimePeriodSpecification ;

//// §5 Lexical Elements

/// §5.3 Literals

intervalLiteral : 'INTERVAL' (PLUS | MINUS)? characterString intervalQualifier? ;

dateTimeLiteral
    : 'DATE' characterString #DateLiteral
    | 'TIMESTAMP' withOrWithoutTimeZone? characterString #TimestampLiteral
    ;


literal
    : ('+' | '-')? UNSIGNED_FLOAT #FloatLiteral
    | ('+' | '-')? UNSIGNED_INTEGER #IntegerLiteral
    | characterString #CharacterStringLiteral
    | BINARY_STRING #BinaryStringLiteral
    | dateTimeLiteral #DateTimeLiteral0
    | 'TIME' characterString #TimeLiteral
    | intervalLiteral #IntervalLiteral0
    | 'DURATION' characterString #DurationLiteral
    | 'UUID' characterString #UUIDLiteral
    | (TRUE | FALSE) #BooleanLiteral
    | NULL #NullLiteral
    ;

characterString
    : CHARACTER_STRING #SqlStandardString
    | C_ESCAPES_STRING #CEscapesString
    ;

intervalQualifier : startField 'TO' endField | singleDatetimeField ;
startField : nonSecondPrimaryDatetimeField ;
endField : singleDatetimeField ;
intervalFractionalSecondsPrecision : UNSIGNED_INTEGER ;
nonSecondPrimaryDatetimeField : 'YEAR' | 'MONTH' | 'DAY' | 'HOUR' | 'MINUTE' ;
singleDatetimeField : nonSecondPrimaryDatetimeField | 'SECOND' ( LPAREN intervalFractionalSecondsPrecision RPAREN )? ;

/// §5.4 Identifiers

identifierChain : identifier ( '.' identifier )* ;

identifier
    : (REGULAR_IDENTIFIER
        | 'START' | 'END'
        | 'AGE'
        | 'COMMITTED' | 'UNCOMMITTED'
        | 'TIMEZONE'
        | 'VERSION'
        | 'LATEST' | 'SUBMITTED'
        | 'SYSTEM_TIME' | 'VALID_TIME'
        | 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'ERASE'
        | 'SETTING'
        | setFunctionType )
        # RegularIdentifier
    | DELIMITED_IDENTIFIER # DelimitedIdentifier
    ;

schemaName : identifier ;
tableName : (identifier | schemaName '.' identifier) ;
columnName : identifier ;
correlationName : identifier ;

queryName : identifier ;
fieldName : identifier ;
windowName : identifier ;

// §6 Scalar Expressions

/// §6.1 Data Types

dataType
    : ('NUMERIC' | 'DECIMAL' | 'DEC') (LPAREN precision (',' scale)? RPAREN)? # DecimalType
    | ('SMALLINT' | 'INTEGER' | 'INT' | 'BIGINT') # IntegerType
    | 'FLOAT' (LPAREN precision RPAREN)? # FloatType
    | 'REAL' # RealType
    | 'DOUBLE' 'PRECISION' # DoubleType
    | 'BOOLEAN' # BooleanType
    | 'DATE' # DateType
    | 'TIME' (LPAREN precision RPAREN)? withOrWithoutTimeZone? # TimeType
    | 'TIMESTAMP' (LPAREN precision RPAREN)? withOrWithoutTimeZone? # TimestampType
    | 'TIMESTAMPTZ' #TimestampTzType
    | 'INTERVAL' intervalQualifier? # IntervalType
    | ('VARCHAR' | 'TEXT') # CharacterStringType
    | 'DURATION' (LPAREN precision RPAREN)? # DurationType
    | 'ROW' LPAREN fieldDefinition (',' fieldDefinition)* RPAREN # RowType
    | 'REGCLASS' #RegClassType
    | 'REGPROC' #RegProcType
    | dataType 'ARRAY' (LBRACK maximumCardinality RBRACK)? # ArrayType
    ;

precision : UNSIGNED_INTEGER ;
scale : UNSIGNED_INTEGER ;

charLengthUnits : 'CHARACTERS' | 'OCTETS' ;

withOrWithoutTimeZone
    : 'WITH' 'TIME' 'ZONE' #WithTimeZone
    | 'WITHOUT' 'TIME' 'ZONE' #WithoutTimeZone
    ;

maximumCardinality : UNSIGNED_INTEGER ;

fieldDefinition : fieldName dataType ;

/// §6.3 <value expression primary>

expr
    : expr 'IS' NOT? booleanValue #IsBooleanValueExpr
    | expr compOp expr # ComparisonPredicate
    | numericExpr NOT? 'BETWEEN' (ASYMMETRIC | SYMMETRIC)? numericExpr 'AND' numericExpr # BetweenPredicate
    | expr NOT? 'IN' inPredicateValue # InPredicate
    | expr 'NOT'? 'LIKE' likePattern ('ESCAPE' likeEscape)? # LikePredicate
    | expr 'NOT'? 'LIKE_REGEX' xqueryPattern ('FLAG' xqueryOptionFlag)? # LikeRegexPredicate
    | expr postgresRegexOperator xqueryPattern # PostgresRegexPredicate
    | expr 'IS' 'NOT'? 'NULL' # NullPredicate

    // period predicates
    | expr 'OVERLAPS' expr # PeriodOverlapsPredicate
    | expr 'EQUALS' expr # PeriodEqualsPredicate
    | expr 'CONTAINS' expr # PeriodContainsPredicate
    | expr 'PRECEDES' expr # PeriodPrecedesPredicate
    | expr 'SUCCEEDS' expr # PeriodSucceedsPredicate
    | expr 'IMMEDIATELY' 'PRECEDES' expr # PeriodImmediatelyPrecedesPredicate
    | expr 'IMMEDIATELY' 'SUCCEEDS' expr # PeriodImmediatelySucceedsPredicate

    | expr compOp quantifier quantifiedComparisonPredicatePart3 # QuantifiedComparisonPredicate
    | 'NOT' expr #UnaryNotExpr
    | expr 'AND' expr #AndExpr
    | expr 'OR' expr #OrExpr

    | numericExpr #NumericExpr0
    ;

numericExpr
    : '+' numericExpr #UnaryPlusExpr
    | '-' numericExpr #UnaryMinusExpr
    | numericExpr (SOLIDUS | ASTERISK) numericExpr #NumericFactorExpr
    | numericExpr (PLUS | MINUS) numericExpr #NumericTermExpr
    | exprPrimary #ExprPrimary1
    ;

exprPrimary
    : LPAREN expr RPAREN #WrappedExpr
    | literal # LiteralExpr
    | exprPrimary '.' fieldName #FieldAccess
    | exprPrimary LBRACK expr RBRACK #ArrayAccess
    | exprPrimary '::' dataType #PostgresCastExpr
    | exprPrimary '||' exprPrimary #ConcatExpr

    | parameterSpecification # ParamExpr
    | columnReference # ColumnExpr
    | aggregateFunction # AggregateFunctionExpr
    | windowFunctionType 'OVER' windowNameOrSpecification # WindowFunctionExpr
    | nestedWindowFunction # NestedWindowFunctionExpr
    | subquery # ScalarSubqueryExpr
    | 'NEST_ONE' subquery # NestOneSubqueryExpr
    | 'NEST_MANY' subquery # NestManySubqueryExpr
    | 'CASE' expr simpleWhenClause+ elseClause? 'END' #SimpleCaseExpr
    | 'CASE' searchedWhenClause+ elseClause? 'END' # SearchedCaseExpr
    | 'NULLIF' LPAREN expr ',' expr RPAREN # NullIfExpr
    | 'COALESCE' LPAREN expr (',' expr)* RPAREN # CoalesceExpr
    | 'CAST' LPAREN expr 'AS' dataType RPAREN # CastExpr
    | arrayValueConstructor # ArrayExpr
    | objectConstructor # ObjectExpr
    | generateSeries # GenerateSeriesFunction

    | 'EXISTS' subquery # ExistsPredicate

    | (schemaName '.')? 'HAS_ANY_COLUMN_PRIVILEGE' LPAREN
        ( userString ',' )?
        tableString ','
        privilegeString
      RPAREN # HasAnyColumnPrivilegePredicate

    | (schemaName '.')? 'HAS_TABLE_PRIVILEGE' LPAREN
        ( userString ',' )?
        tableString ','
        privilegeString
      RPAREN # HasTablePrivilegePredicate

    | (schemaName '.')? 'HAS_SCHEMA_PRIVILEGE' LPAREN
        ( userString ',' )?
        schemaString ','
        privilegeString
      RPAREN # HasSchemaPrivilegePredicate

    | (schemaName '.')? 'VERSION' LPAREN RPAREN #PostgresVersionFunction

    // numeric value functions
    | 'POSITION' LPAREN expr 'IN' expr ( 'USING' charLengthUnits )? RPAREN # PositionFunction
    | 'EXTRACT' LPAREN extractField 'FROM' extractSource RPAREN # ExtractFunction
    | ('CHAR_LENGTH' | 'CHARACTER_LENGTH') LPAREN expr ('USING' charLengthUnits)? RPAREN # CharacterLengthFunction
    | 'OCTET_LENGTH' LPAREN expr RPAREN # OctetLengthFunction
    | 'LENGTH' LPAREN expr RPAREN # LengthFunction
    | 'CARDINALITY' LPAREN expr RPAREN # CardinalityFunction
    | 'ARRAY_UPPER' LPAREN expr ',' expr RPAREN # ArrayUpperFunction
    | 'ABS' LPAREN expr RPAREN # AbsFunction
    | 'MOD' LPAREN expr ',' expr RPAREN # ModFunction
    | trigonometricFunctionName LPAREN expr RPAREN # TrigonometricFunction
    | 'LOG' LPAREN generalLogarithmBase ',' generalLogarithmArgument RPAREN # LogFunction
    | 'LOG10' LPAREN expr RPAREN # Log10Function
    | 'LN' LPAREN expr RPAREN # LnFunction
    | 'EXP' LPAREN expr RPAREN # ExpFunction
    | 'POWER' LPAREN expr ',' expr RPAREN # PowerFunction
    | 'SQRT' LPAREN expr RPAREN # SqrtFunction
    | 'FLOOR' LPAREN expr RPAREN # FloorFunction
    | ( 'CEIL' | 'CEILING' ) LPAREN expr RPAREN # CeilingFunction
    | 'LEAST' LPAREN expr (',' expr)* RPAREN # LeastFunction
    | 'GREATEST' LPAREN expr (',' expr)* RPAREN # GreatestFunction

    // string value functions
    | 'SUBSTRING' LPAREN
        expr
        'FROM' startPosition
        ( 'FOR' stringLength )?
        ( 'USING' charLengthUnits )?
      RPAREN # CharacterSubstringFunction

    | 'UPPER' LPAREN expr RPAREN # UpperFunction
    | 'LOWER' LPAREN expr RPAREN # LowerFunction
    | 'TRIM' LPAREN trimSpecification? trimCharacter? 'FROM'? trimSource RPAREN # TrimFunction

    | 'OVERLAY' LPAREN
        expr
        'PLACING' expr
        'FROM' startPosition
        ( 'FOR' stringLength )?
        ( 'USING' charLengthUnits )?
      RPAREN # OverlayFunction

    | 'REPLACE' LPAREN expr ',' replaceTarget ',' replacement RPAREN # ReplaceFunction

    | (schemaName '.')? 'CURRENT_USER' # CurrentUserFunction
    | (schemaName '.')? 'CURRENT_SCHEMA' (LPAREN RPAREN)? # CurrentSchemaFunction
    | (schemaName '.')? 'CURRENT_SCHEMAS' LPAREN expr RPAREN # CurrentSchemasFunction
    | (schemaName '.')? 'CURRENT_DATABASE' (LPAREN RPAREN)? # CurrentDatabaseFunction
    | (schemaName '.')? 'PG_GET_EXPR' (LPAREN expr ',' expr (',' expr)? RPAREN)? # PgGetExprFunction
    | (schemaName '.')? '_PG_EXPANDARRAY' (LPAREN expr RPAREN)? # PgExpandArrayFunction

    | currentInstantFunction # CurrentInstantFunction0
    | 'CURRENT_TIME' (LPAREN precision RPAREN)? # CurrentTimeFunction
    | 'LOCALTIME' (LPAREN precision RPAREN)? # LocalTimeFunction
    | 'DATE_TRUNC' LPAREN dateTruncPrecision ',' dateTruncSource (',' dateTruncTimeZone)? RPAREN # DateTruncFunction
    | 'DATE_BIN' LPAREN intervalLiteral ',' dateBinSource (',' dateBinOrigin)? RPAREN # DateBinFunction
    | 'RANGE_BINS' LPAREN intervalLiteral ',' rangeBinsSource (',' dateBinOrigin)? RPAREN #RangeBinsFunction
    | 'OVERLAPS' LPAREN expr ( ',' expr )+ RPAREN # OverlapsFunction
    | ('PERIOD' | 'TSTZRANGE') LPAREN expr ',' expr RPAREN # TsTzRangeConstructor
    | 'UPPER_INF' LPAREN expr RPAREN # UpperInfFunction
    | 'LOWER_INF' LPAREN expr RPAREN # LowerInfFunction

    // interval value functions
    | 'AGE' LPAREN expr ',' expr RPAREN # AgeFunction

    | 'TRIM_ARRAY' LPAREN expr ',' expr RPAREN # TrimArrayFunction
    ;

replaceTarget : expr;
replacement : expr;

currentInstantFunction
    : 'CURRENT_DATE' ( LPAREN RPAREN )? # CurrentDateFunction
    | ('CURRENT_TIMESTAMP' | 'NOW') (LPAREN precision RPAREN)? # CurrentTimestampFunction
    | 'LOCALTIMESTAMP' (LPAREN precision RPAREN)? # LocalTimestampFunction
    ;

booleanValue : 'TRUE' | 'FALSE' | 'UNKNOWN' ;

// spec addition: objectConstructor

objectConstructor
    : ('RECORD' | 'OBJECT') LPAREN (objectNameAndValue (',' objectNameAndValue)*)? RPAREN
    | LBRACE (objectNameAndValue (',' objectNameAndValue)*)? RBRACE
    ;

objectNameAndValue : objectName ':' expr ;

objectName : identifier ;

parameterSpecification
    : '?' #DynamicParameter
    | POSTGRES_PARAMETER_SPECIFICATION #PostgresParameter
    ;

columnReference : identifierChain ;

/// generate_series function

generateSeries : (schemaName '.')? 'GENERATE_SERIES' LPAREN seriesStart ',' seriesEnd (',' seriesStep)? RPAREN ;
seriesStart: expr;
seriesEnd: expr;
seriesStep: expr;

/// §6.10 <window function>

windowFunctionType
    : rankFunctionType LPAREN RPAREN # RankWindowFunction
    | 'ROW_NUMBER' LPAREN RPAREN # RowNumberWindowFunction
    | aggregateFunction # AggregateWindowFunction
    | 'NTILE' LPAREN numberOfTiles RPAREN # NtileWindowFunction

    | ('LEAD' | 'LAG') LPAREN
        leadOrLagExtent
        (',' offset (',' defaultExpression)?)?
      RPAREN (nullTreatment)? # LeadOrLagWindowFunction

    | firstOrLastValue LPAREN expr RPAREN nullTreatment? # FirstOrLastValueWindowFunction
    | 'NTH_VALUE' LPAREN expr ',' nthRow RPAREN fromFirstOrLast? nullTreatment? # NthValueWindowFunction
;

rankFunctionType : 'RANK' | 'DENSE_RANK' | 'PERCENT_RANK' | 'CUME_DIST' ;

numberOfTiles : UNSIGNED_INTEGER | parameterSpecification ;

leadOrLagExtent : expr ;
offset : UNSIGNED_INTEGER ;
defaultExpression : expr ;

nullTreatment
    : 'RESPECT' 'NULLS' # RespectNulls
    | 'IGNORE' 'NULLS' # IgnoreNulls
    ;

firstOrLastValue : 'FIRST_VALUE' | 'LAST_VALUE' ;

nthRow : UNSIGNED_INTEGER | '?' ;

fromFirstOrLast
    : 'FROM' 'FIRST' # FromFirst
    | 'FROM' 'LAST' # FromLast
    ;

windowNameOrSpecification : windowName | windowSpecification ;

/// §6.11 <nested window function>

nestedWindowFunction
    : 'ROW_NUMBER' LPAREN rowMarker RPAREN # NestedRowNumberFunction
    | 'VALUE_OF' LPAREN expr 'AT' rowMarkerExpression (',' valueOfDefaultValue)? RPAREN # ValueOfExprAtRow
    ;

rowMarker : 'BEGIN_PARTITION' | 'BEGIN_FRAME' | 'CURRENT_ROW' | 'FRAME_ROW' | 'END_FRAME' | 'END_PARTITION' ;

rowMarkerExpression : rowMarker rowMarkerDelta? ;

rowMarkerDelta : PLUS rowMarkerOffset | MINUS rowMarkerOffset ;

rowMarkerOffset : UNSIGNED_INTEGER | '?' ;
valueOfDefaultValue : expr ;

/// §6.12 <case expression>

simpleWhenClause : 'WHEN' whenOperandList 'THEN' expr ;
searchedWhenClause : 'WHEN' expr 'THEN' expr ;
elseClause : 'ELSE' expr ;

whenOperandList : whenOperand (',' whenOperand)* ;
whenOperand : predicatePart2 | expr ;

/// §6.28 <numeric value function>

extractField : primaryDatetimeField | timeZoneField ;
primaryDatetimeField : nonSecondPrimaryDatetimeField | 'SECOND' ;
timeZoneField : 'TIMEZONE_HOUR' | 'TIMEZONE_MINUTE' ;
extractSource : expr ;

/// §6.30 <string value function>

trimSource : expr ;
trimSpecification : 'LEADING' | 'TRAILING' | 'BOTH' ;
trimCharacter : expr ;

startPosition : expr ;
stringLength : expr ;

/// §6.32 <datetime value function>

// spec additions for date_trunc

dateTruncPrecision
    : 'MILLENNIUM' | 'CENTURY' | 'DECADE'
    | 'YEAR' | 'QUARTER' | 'MONTH' | 'WEEK' | 'DAY'
    | 'HOUR' | 'MINUTE' | 'SECOND'
    | 'MILLISECOND' | 'MICROSECOND' | 'NANOSECOND'
    ;

dateTruncSource : expr ;
dateTruncTimeZone : characterString ;

dateBinSource : expr ;
rangeBinsSource : expr ;
dateBinOrigin : expr ;

/// §6.34 <interval value function>

// spec addition: age function

/// §6.37 <array value function>

/// §6.38 <array value constructor>

arrayValueConstructor
    : 'ARRAY'? LBRACK (expr (',' expr)*)? RBRACK # ArrayValueConstructorByEnumeration
    | 'ARRAY' subquery # ArrayValueConstructorByQuery
    ;

/// SQL:2016 §6.30 Trigonometric functions

trigonometricFunctionName : 'SIN' | 'COS' | 'TAN' | 'SINH' | 'COSH' | 'TANH' | 'ASIN' | 'ACOS' | 'ATAN' ;

// general_logarithm_function

generalLogarithmBase : expr ;
generalLogarithmArgument : expr ;

//// §7 Query expressions

/// §7.1 <row value constructor>

rowValueConstructor
    : expr # SingleExprRowConstructor
    | LPAREN ( expr (',' expr)+ )? RPAREN  # MultiExprRowConstructor
    | 'ROW' LPAREN ( expr (',' expr)* )? RPAREN # MultiExprRowConstructor
    ;

/// §7.3 <table value constructor>

rowValueList : rowValueConstructor (',' rowValueConstructor)* ;
tableValueConstructor : 'VALUES' rowValueList ;

recordValueConstructor
    : parameterSpecification # ParameterRecord
    | objectConstructor # ObjectRecord
    ;

recordsValueList : recordValueConstructor (',' recordValueConstructor)* ;

recordsValueConstructor : 'RECORDS' recordsValueList ;

/// §7.5 <from clause>

fromClause : 'FROM' tableReference (',' tableReference)* ;

/// §7.6 <table reference>

tableReference
    : tableOrQueryName (querySystemTimePeriodSpecification | queryValidTimePeriodSpecification)* tableAlias? tableProjection? # BaseTable
    | tableReference joinType? 'JOIN' tableReference joinSpecification # JoinTable
    | tableReference 'CROSS' 'JOIN' tableReference # CrossJoinTable
    | tableReference 'NATURAL' joinType? 'JOIN' tableReference # NaturalJoinTable
    | subquery tableAlias tableProjection? # DerivedTable
    | 'LATERAL' subquery tableAlias tableProjection? # LateralDerivedTable
    | 'UNNEST' LPAREN expr RPAREN withOrdinality? tableAlias tableProjection? # CollectionDerivedTable
    | generateSeries tableAlias tableProjection? # GenerateSeriesTable
    | 'ARROW_TABLE' LPAREN characterString RPAREN tableAlias tableProjection # ArrowTable
    | LPAREN tableReference RPAREN # WrappedTableReference
    ;

withOrdinality : ('WITH' 'ORDINALITY') ;

tableAlias : 'AS'? correlationName ;
tableProjection : LPAREN columnNameList RPAREN ;

querySystemTimePeriodSpecification
    : 'FOR' 'SYSTEM_TIME' tableTimePeriodSpecification
    | 'FOR' ALL 'SYSTEM_TIME'
    ;

queryValidTimePeriodSpecification
   : 'FOR' 'VALID_TIME' tableTimePeriodSpecification
   | 'FOR' ALL 'VALID_TIME'
   ;

tableTimePeriodSpecification
    : 'AS' 'OF' periodSpecificationExpr # TableAsOf
    | 'ALL' # TableAllTime
    | 'BETWEEN' periodSpecificationExpr 'AND' periodSpecificationExpr # TableBetween
    | 'FROM' periodSpecificationExpr 'TO' periodSpecificationExpr # TableFromTo
    ;

periodSpecificationExpr
    : literal #PeriodSpecLiteral
    | parameterSpecification #PeriodSpecParam
    | ('NOW' | 'CURRENT_TIMESTAMP') #PeriodSpecNow
    ;

tableOrQueryName : tableName ;

columnNameList : columnName (',' columnName)* ;

/// §7.7 <joined table>

joinSpecification
    : 'ON' expr # JoinCondition
    | 'USING' LPAREN columnNameList RPAREN # NamedColumnsJoin
    ;

joinType : 'INNER' | outerJoinType 'OUTER'? ;
outerJoinType : 'LEFT' | 'RIGHT' | 'FULL' ;

/// §7.8 <where clause>

whereClause : 'WHERE' expr ;

/// §7.9 <group by clause>

groupByClause : 'GROUP' 'BY' (setQuantifier)? groupingElement (',' groupingElement)* ;

groupingElement
    : columnReference # OrdinaryGroupingSet
    | LPAREN RPAREN # EmptyGroupingSet
    ;

/// §7.10 <having clause>

havingClause : 'HAVING' expr ;

/// §7.11 <window clause>

windowClause : 'WINDOW' windowDefinitionList ;
windowDefinitionList : windowDefinition (',' windowDefinition)* ;
windowDefinition : newWindowName 'AS' windowSpecification ;
newWindowName : windowName ;

windowSpecification : LPAREN windowSpecificationDetails RPAREN ;

windowSpecificationDetails : (existingWindowName)? (windowPartitionClause)? (windowOrderClause)? (windowFrameClause)? ;

existingWindowName : windowName ;
windowPartitionClause : 'PARTITION' 'BY' windowPartitionColumnReferenceList ;
windowPartitionColumnReferenceList : windowPartitionColumnReference (',' windowPartitionColumnReference)* ;
windowPartitionColumnReference : columnReference ;
windowOrderClause : 'ORDER' 'BY' sortSpecificationList ;
windowFrameClause : windowFrameUnits windowFrameExtent (windowFrameExclusion)? ;
windowFrameUnits : 'ROWS' | 'RANGE' | 'GROUPS' ;
windowFrameExtent : windowFrameStart | windowFrameBetween ;
windowFrameStart : 'UNBOUNDED' 'PRECEDING' | windowFramePreceding | 'CURRENT' 'ROW' ;
windowFramePreceding : UNSIGNED_INTEGER 'PRECEDING' ;
windowFrameBetween : 'BETWEEN' windowFrameBound1 'AND' windowFrameBound2 ;
windowFrameBound1 : windowFrameBound ;
windowFrameBound2 : windowFrameBound ;
windowFrameBound : windowFrameStart | 'UNBOUNDED' 'FOLLOWING' | windowFrameFollowing ;
windowFrameFollowing : UNSIGNED_INTEGER 'FOLLOWING' ;

windowFrameExclusion : 'EXCLUDE' 'CURRENT' 'ROW' | 'EXCLUDE' 'GROUP' | 'EXCLUDE' 'TIES' | 'EXCLUDE' 'NO' 'OTHERS' ;

/// §7.12 <query specification>

selectClause : 'SELECT' setQuantifier? selectList ;
selectList : (selectListAsterisk | selectSublist) (',' selectSublist)* ;

selectListAsterisk : ASTERISK excludeClause? renameClause? ;

selectSublist : derivedColumn | qualifiedAsterisk ;
qualifiedAsterisk : identifierChain '.' ASTERISK excludeClause? qualifiedRenameClause?;

renameClause
    : 'RENAME' renameColumn
    | 'RENAME' LPAREN renameColumn (',' renameColumn )* RPAREN
    ;

renameColumn : columnReference asClause ;

qualifiedRenameClause
    : 'RENAME' qualifiedRenameColumn
    | 'RENAME' LPAREN qualifiedRenameColumn (',' qualifiedRenameColumn )* RPAREN
    ;

qualifiedRenameColumn : identifier asClause ;

excludeClause
    : 'EXCLUDE' identifier
    | 'EXCLUDE' LPAREN identifier (',' identifier )* RPAREN
    ;

derivedColumn : expr asClause? ;
asClause : 'AS'? columnName ;

/// §7.13 <query expression>

queryExpression : withClause? queryExpressionNoWith ;
queryExpressionNoWith : queryExpressionBody orderByClause? offsetAndLimit?  ;
withClause : 'WITH' RECURSIVE? withListElement (',' withListElement)* ;
withListElement : queryName (LPAREN columnNameList RPAREN)? 'AS' subquery ;

queryExpressionBody
    : queryTerm # QueryBodyTerm
    | queryExpressionBody 'UNION' (ALL | DISTINCT)? queryTerm # UnionQuery
    | queryExpressionBody 'EXCEPT' (ALL | DISTINCT)? queryTerm # ExceptQuery
    ;

queryTerm
    : selectClause fromClause? whereClause? groupByClause? havingClause? windowClause? # QuerySpecification
    | fromClause whereClause? groupByClause? havingClause? selectClause? windowClause? # QuerySpecification
    | tableValueConstructor # ValuesQuery
    | recordsValueConstructor # RecordsQuery
    | xtqlForm # XtqlQuery
    | LPAREN queryExpressionNoWith RPAREN # WrappedQuery
    | queryTerm 'INTERSECT' (ALL | DISTINCT)? queryTerm # IntersectQuery
    ;

orderByClause : 'ORDER' 'BY' sortSpecificationList ;

offsetAndLimit
    : resultOffsetClause fetchFirstClause?
    | fetchFirstClause resultOffsetClause?
    ;

resultOffsetClause : 'OFFSET' offsetRowCount ( 'ROW' | 'ROWS' )? ;

fetchFirstClause
    : 'FETCH' ('FIRST' | 'NEXT') fetchFirstRowCount? ( 'ROW' | 'ROWS' ) 'ONLY'
    | 'LIMIT' fetchFirstRowCount
    ;

offsetRowCount : UNSIGNED_INTEGER | parameterSpecification ;
fetchFirstRowCount : UNSIGNED_INTEGER | parameterSpecification ;

/// §7.15 <subquery>

subquery : LPAREN queryExpression RPAREN ;

//// §8 Predicates

predicatePart2
    : compOp expr # ComparisonPredicatePart2
    | NOT? 'BETWEEN' (ASYMMETRIC | SYMMETRIC)? expr 'AND' expr # BetweenPredicatePart2
    | NOT? 'IN' inPredicateValue # InPredicatePart2
    | 'NOT'? 'LIKE' likePattern ('ESCAPE' likeEscape)? # LikePredicatePart2
    | 'NOT'? 'LIKE_REGEX' xqueryPattern ('FLAG' xqueryOptionFlag)? # LikeRegexPredicatePart2
    | postgresRegexOperator xqueryPattern # PostgresRegexPredicatePart2
    | 'IS' 'NOT'? 'NULL' # NullPredicatePart2
    | compOp quantifier quantifiedComparisonPredicatePart3 # QuantifiedComparisonPredicatePart2
    ;

quantifiedComparisonPredicatePart3
  : subquery # QuantifiedComparisonSubquery
  | expr #QuantifiedComparisonExpr
  ;

compOp : '=' | '!=' | '<>' | '<' | '>' | '<=' | '>=' ;

inPredicateValue
    : subquery # InSubquery
    | LPAREN rowValueList RPAREN # InRowValueList
    ;

likePattern : exprPrimary ;
likeEscape : exprPrimary ;

xqueryPattern : exprPrimary ;
xqueryOptionFlag : exprPrimary ;

postgresRegexOperator : '~' | '~*' | '!~' | '!~*' ;

quantifier : 'ALL' | 'SOME' | 'ANY' ;

/// §8.21 <search condition>

searchCondition : expr ;

/// postgres access privilege predicates

userString : expr ;
tableString : expr ;
schemaString : expr ;
privilegeString : expr ;

//// §10 Additional common elements

/// §10.9 <aggregate function>

aggregateFunction
    : 'COUNT' LPAREN ASTERISK RPAREN # CountStarFunction
    | 'ARRAY_AGG' LPAREN expr ('ORDER' 'BY' sortSpecificationList)? RPAREN # ArrayAggFunction
    | setFunctionType LPAREN setQuantifier? expr RPAREN # SetFunction
    ;

setFunctionType
    : 'AVG' | 'MAX' | 'MIN' | 'SUM' | 'COUNT'
    // Removed 'ANY' and 'SOME' as aggregate functions, as it introduces ambiguities with
    // the `= ANY` comparison operator.  (Following the same approach as PostgreSQL).
    | 'EVERY' | 'BOOL_AND' | 'BOOL_OR'
    | 'STDDEV_POP' | 'STDDEV_SAMP' | 'VAR_SAMP' | 'VAR_POP' ;

setQuantifier : 'DISTINCT' | 'ALL' ;

/// §10.10 <sort specification list>

sortSpecificationList : sortSpecification (',' sortSpecification)* ;
sortSpecification : expr orderingSpecification? nullOrdering? ;
orderingSpecification : 'ASC' | 'DESC' ;
nullOrdering : 'NULLS' 'FIRST' | 'NULLS' 'LAST' ;

/// §14 Data manipulation

/// §14.9 <delete statement: searched>

deleteStatementSearched
    : 'DELETE' 'FROM' tableName
      dmlStatementValidTimeExtents?
      ( 'AS'? correlationName )?
      ( 'WHERE' searchCondition )?
    ;

dmlStatementValidTimeExtents
  : 'FOR' ('PORTION' 'OF')? 'VALID_TIME' 'FROM' expr 'TO' expr # DmlStatementValidTimePortion
  | 'FOR' ('ALL' 'VALID_TIME' | 'VALID_TIME' 'ALL') # DmlStatementValidTimeAll
  ;

eraseStatementSearched : 'ERASE' 'FROM' tableName ( 'AS'? correlationName )? ('WHERE' searchCondition)? ;

/// §14.11 <insert statement>

insertStatement : 'INSERT' 'INTO' tableName insertColumnsAndSource ;
insertColumnsAndSource
    : ( LPAREN columnNameList RPAREN )? tableValueConstructor # InsertValues
    | ( LPAREN columnNameList RPAREN )? recordsValueConstructor # InsertRecords
    | ( LPAREN columnNameList RPAREN )? queryExpression # InsertFromSubquery
    ;

/// §14.14 <update statement: searched>

updateStatementSearched
    : 'UPDATE' tableName
      dmlStatementValidTimeExtents?
      ( 'AS'? correlationName )?
      'SET' setClauseList
      ( 'WHERE' searchCondition )?
    ;

/// §14.15 <set clause list>

setClauseList : setClause (',' setClause)* ;
setClause : setTarget '=' updateSource ;

// TODO SQL:2011 supports updating keys within a struct here
setTarget : columnName ;

updateSource : expr ;

/// §17.3 <transaction characteristics>

sessionCharacteristic
    : 'TRANSACTION' sessionTxMode (',' sessionTxMode)* # SessionTxCharacteristics
    ;

sessionTxMode
    : 'ISOLATION' 'LEVEL' levelOfIsolation  # SessionIsolationLevel
    | 'READ' 'ONLY' # ReadOnlySession
    | 'READ' 'WRITE' # ReadWriteSession
    ;

transactionCharacteristics : transactionMode (',' transactionMode)* ;

transactionMode
    : 'ISOLATION' 'LEVEL' levelOfIsolation  # IsolationLevel
    | 'READ' 'ONLY' # ReadOnlyTransaction
    | 'READ' 'WRITE' # ReadWriteTransaction
    | 'AT' 'SYSTEM_TIME' dateTimeLiteral #TransactionSystemTime
    ;

levelOfIsolation
    : 'READ' 'UNCOMMITTED' # ReadUncommittedIsolation
    | 'READ' 'COMMITTED' # ReadCommittedIsolation
    | 'REPEATABLE' 'READ' # RepeatableReadIsolation
    | 'SERIALIZABLE' # SerializableIsolation
    ;

/// XTQL

xtqlForm
  : XTQL_SCALAR # XtqlScalar
  | XTQL_LPAREN (xtqlForm | xtqlDiscard)* XTQL_RPAREN # XtqlList
  | XTQL_LBRACK (xtqlForm | xtqlDiscard)* XTQL_RBRACK # XtqlVector
  | XTQL_LBRACE (xtqlForm | xtqlDiscard)* XTQL_RBRACE # XtqlMap
  | '#{' (xtqlForm | xtqlDiscard)* XTQL_RBRACE # XtqlSet
  | readerTag=XTQL_READER_TAG xtqlForm # XtqlReaderMacro
  ;

xtqlDiscard : '#_' xtqlForm ;
