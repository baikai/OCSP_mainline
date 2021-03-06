package com.asiainfo.ocdp.stream.tools

import com.asiainfo.ocdp.stream.config.DataSchema
import com.asiainfo.ocdp.stream.common.{JDBCUtil, Logging}
import org.apache.spark.sql.types._
import org.json4s.DefaultFormats
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import com.asiainfo.ocdp.stream.common.Logging
import scala.collection.immutable.HashMap
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

/**
 * Created by tsingfu on 15/8/18.
 */
object Json4sUtils extends Logging {

  /**
    *
    * @param jsonStr input string with json format
    * @param fields  Json fields name
    * @return output string concatnated by comma
    */
  def jsonStr2String(jsonStr: String, fields: Array[String],delim:String): String =
    jsonStr2ArrTuple2(jsonStr, fields).map(tuple => tuple._2).mkString(delim)

    /**
    *
    * @param jsonStr schema for struct data with fieldName and FieldDataType, in json string format
    * @param udfFieldName fieldName for schema json string
    * @return structType of sparkSQL for the schema
    */
  def jsonStr2UserStructType(jsonStr: String, udfFieldName: String, commonSchema: StructType): StructType = {

    val commonFields = commonSchema.fieldNames.toSet
    val rawFields = mutable.Set[String]()
    val fieldsNameTypeArrMap =(jsonStr2ArrMap(jsonStr, udfFieldName))

    var fieldsInfo = fieldsNameTypeArrMap.map(fieldsNameTypeMap => {
      var field_expr = ""
      if (fieldsNameTypeMap.contains("pvalue")) field_expr = fieldsNameTypeMap("pvalue") + " as " + fieldsNameTypeMap("pname")
      else field_expr = fieldsNameTypeMap("pname")
      rawFields += fieldsNameTypeMap("pname")
      (field_expr, "String", "true")
    }).toBuffer

    if (!commonFields.isEmpty) {
      val lessInput = rawFields -- commonFields
      val moreInput = commonFields -- rawFields
      if (lessInput.isEmpty) {
        //FIXME ignore or goes wrong
        //throw new Exception("Wrong CommonFields with UserFileds!")
      }
      for (field <- moreInput) {
        fieldsInfo += Tuple3(field, "String", "true")
      }
    }
    if (fieldsInfo.isEmpty) {
      throw new Exception("No main fields !!!")
    }

    generateStructType(fieldsInfo.toArray)
  }
  /**
    * filter user filed in common schema
    * @param jsonStr schema for struct data with fieldName and FieldDataType, in json string format
    * @param udfFieldName fieldName for schema json string
    * @return structType of sparkSQL for the schema
    */
  def jsonStrFilterUserStructType(jsonStr: String, udfFieldName: String, commonSchema: StructType): StructType = {

    val commonFields = commonSchema.fieldNames.toSet
    val rawFields = mutable.Set[String]()
    val fieldsNameTypeArrMap =(jsonStr2ArrMap(jsonStr, udfFieldName))

    var fieldsInfo = fieldsNameTypeArrMap.map(fieldsNameTypeMap => {
      if (commonFields.contains(fieldsNameTypeMap("pname"))) {
        var field_expr = ""
        if (fieldsNameTypeMap.contains("pvalue")) field_expr = fieldsNameTypeMap("pvalue") + " as " + fieldsNameTypeMap("pname")
        else field_expr = fieldsNameTypeMap("pname")
        rawFields += fieldsNameTypeMap("pname")
        Some((field_expr, "String", "true"))
      } else None
    }).collect{case Some(t) => (t)}.toBuffer

    if (!commonFields.isEmpty) {

      val moreInput = commonFields -- rawFields
      for (field <- moreInput) {
        fieldsInfo += Tuple3(field, "String", "true")
      }
    }
    if (fieldsInfo.isEmpty) {
      throw new Exception("No main fields !!!")
    }

    generateStructType(fieldsInfo.toArray)
  }

  /**
    * @param jsonStr schema for struct data with fieldName and FieldDataType, in json string format
    * @param fieldName fieldName for sourceConfs json string
    * @return structType of sparkSQL for the schema
    */
  def jsonStr2DataSchemas(jsonStr: String, fieldName: String, commonSchema: StructType): Array[DataSchema] = {

    val arraySchema = new ArrayBuffer[DataSchema]()
    val jsonSources = parse(jsonStr) \ fieldName
    for {
      JArray(objList) <- jsonSources
      JObject(obj) <- objList
      JField("pname", JString(name)) <- obj
      JField("delim", JString(delim)) <- obj
      JField("topic", JString(topic)) <- obj
    } {
      val dataSchema = new DataSchema
      dataSchema.setName(name.toString)
      dataSchema.setDelim(delim.toString)
      dataSchema.setTopic(topic.toString)
      dataSchema.setRawSchema(jsonStr2BaseStructType(compact(obj), "fields"))
      dataSchema.setRawSchemaSize((jsonStr2ArrMap(compact(obj), "fields")).size)
      dataSchema.setAllItemsSchema(jsonStr2UserStructType(compact(obj), "userFields", commonSchema))
      dataSchema.setUsedItemsSchema(jsonStrFilterUserStructType(compact(obj), "userFields", commonSchema))

      arraySchema += dataSchema
    }

    arraySchema.toArray
  }

  /**
    *
    * @param jsonStr schema for struct data with fieldName and FieldDataType, in json string format
    * @param arrMapFieldName fieldName for schema json string
    * @return structType of sparkSQL for the schema
    */
  def jsonStr2BaseStructType(jsonStr: String, arrMapFieldName: String): StructType = {
    val fieldsNameTypeArrMap = jsonStr2ArrMap(jsonStr, arrMapFieldName)
    val fieldsInfo = fieldsNameTypeArrMap.map(fieldsNameTypeMap => (fieldsNameTypeMap("pname"), "String", "true"))
    generateStructType(fieldsInfo)
  }

  /**
    *
    * @param jsonStr schema for struct data with fieldName and FieldDataType, in json string format
    * @param baseFieldName fieldName for schema json string
    * @param udfFieldName fieldName for schema json string
    * @return structType of sparkSQL for the schema
    */
  def jsonStr2UdfStructType(jsonStr: String, baseFieldName: String, udfFieldName: String): StructType = {
    val fieldsNameTypeArrMap = jsonStr2ArrMap(jsonStr, baseFieldName).union(jsonStr2ArrMap(jsonStr, udfFieldName))

    val fieldsInfo = fieldsNameTypeArrMap.map(fieldsNameTypeMap => {
      var field_expr = ""
      if (fieldsNameTypeMap.contains("pvalue")) field_expr = fieldsNameTypeMap("pvalue") + " as " + fieldsNameTypeMap("pname")
      else field_expr = fieldsNameTypeMap("pname")
      (field_expr, "String", "true")
    })
    generateStructType(fieldsInfo)
  }

  /**
    *
    * @param jsonStr schema for struct data with fieldName and FieldDataType, in json string format
    * @return structType of sparkSQL for the schema
    */
  def jsonStr2StructType(jsonStr: String): StructType = {
    val fieldsNameTypeArrMap = jsonStr2ArrMap(jsonStr)
    val fieldsInfo = fieldsNameTypeArrMap.map(fieldsNameTypeMap => {
      val pname = fieldsNameTypeMap("pname")
      val ptype = fieldsNameTypeMap("ptype")
      val expr = fieldsNameTypeMap("pvalue")

      var colname = ""
      if (expr.length < 1) colname = pname
      else colname = "(" + expr + ") as " + pname

      (colname, ptype, "true")
    })
    generateStructType(fieldsInfo)
  }

  /**
    * 根据指定字段名和类型信息生成构建SparkSQL DataFrame的schema
    * @param fields 顺序指定字段名、类型、是否允许为空
    * @return 返回构建SparkSQL DataFrame的schema，包含指定字段和一个 `Map[String, Map[String, String]]`
    */
  def generateStructType(fields: Array[(String, String, String)]): StructType = {

    val structFields = fields.map(fieldNameType => {
      val (name, dataTypeStr, containsNullStr) = fieldNameType
      StructField(name, getPrimaryDataType(dataTypeStr), containsNullStr.toBoolean)
    }).toBuffer[StructField]

    val schema = StructType(structFields.toArray)
    schema
  }

  //TODO: 暂不支持复合数据类型
  /**
    * 返回指定基本类型名(字符串形式)对应在SparkSQL中构造 StructField 的类型DataType
    * @param typeNameInScala 指定的字符串形式的类型名
    * @return 返回对应的DataType
    */
  def getPrimaryDataType(typeNameInScala: String): DataType = {
    typeNameInScala.toLowerCase() match {
      case "byte" => ByteType
      case "short" => ShortType
      case "int" => IntegerType
      case "integer" => IntegerType
      case "long" => LongType
      case "float" => FloatType
      case "double" => DoubleType
      case "string" => StringType
      case "array[byte]" => BinaryType
      case "boolean" => BooleanType
      case "java.sql.timestamp" => TimestampType
      case "java.sql.date" => DateType
      case "Byte" => ByteType
      case "Short" => ShortType
      case "Int" => IntegerType
      case "Integer" => IntegerType
      case "Long" => LongType
      case "Float" => FloatType
      case "Double" => DoubleType
      case "String" => StringType
      case "Array[Byte]" => BinaryType
      case "Boolean" => BooleanType
      case "java.sql.Timestamp" => TimestampType
      case "java.sql.Date" => DateType
      //      case _ => throw new Exception("Unsupported data type " + typeNameInScala)
      case _ => StringType
    }
  }

  def jsonStr2ArrMap(jsonStr: String, arrMapFieldName: String): Array[Map[String, String]] = {
    val jsonStr_target = compact(parse(jsonStr) \ arrMapFieldName)
    val jsonValue = parse(jsonStr_target)
    implicit val formats = DefaultFormats
    jsonValue.extract[Array[Map[String, String]]]
  }

  def jsonStr2ArrMap(jsonStr: String): Array[Map[String, String]] = {
    val jsonValue = parse(jsonStr)
    implicit val formats = DefaultFormats
    jsonValue.extract[Array[Map[String, String]]]
  }

  def jsonStr2JValue(jsonStr: String): JValue = {
    parse(jsonStr)
  }

  /**
    * 从 json格式的字符串中获取指定属性的取值
    * @param jsonStr
    * @param fields
    * @return
    */
  def jsonStr2ArrTuple2(jsonStr: String, fields: Array[String]): Array[(String, String)] = {
    val result = ArrayBuffer[(String, String)]()
    for (field <- fields) {
      val jsonStr_target = compact(parse(jsonStr) \ field)
      val prop = parse(jsonStr_target) match {
        case JString(str) => (field, str)
        case _ => (field, null)
      }

      if (prop._2 != null) result.append(prop)
    }
    result.toArray
  }

  def jsonStr2Map(jsonStr: String): Map[String, String] = {
    implicit val formats = DefaultFormats
    parse(jsonStr).extract[Map[String, String]]
  }

  def map2JsonStr(jsonMap: Map[String, String]): String = {
    compact(render(jsonMap))
  }

  def isValidJsonStr(jsonStr: String): Boolean = {
    Try(parse(jsonStr)) match {
      case Success(s) => true
      case Failure(f) => false
    }
  }

  def jsonStr2MapList(jsonStr: String): Map[String, List[Map[String, String]]] = {
    val jsonValue = parse(jsonStr)
    implicit val formats = DefaultFormats
    val result = Try(jsonValue.extract[Map[String, List[Map[String, String]]]])
    result match {
      case Success(s) => result.get
      case Failure(f) => {
        logError("Can not transform json to Map[String, List[Map[String, String]]]")
        new HashMap[String, List[Map[String, String]]]
      }
    }
  }

}
