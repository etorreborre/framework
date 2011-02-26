/*
 * Copyright 2011 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb
package mongodb
package record

import common._
import field.MongoField

import net.liftweb.record.{Field, MetaRecord, Record}
import net.liftweb.record.field._

import com.mongodb._

/** Specialized Record that can be encoded and decoded from BSON (DBObject) */
trait BsonRecord[MyType <: BsonRecord[MyType]] extends Record[MyType] {
  self: MyType =>
  
  /** Refines meta to require a BsonMetaRecord */
  def meta: BsonMetaRecord[MyType]
  
  /**
  * Encode a record instance into a DBObject
  */
  def asDBObject: DBObject = meta.asDBObject(this)
  
  /**
  * Set the fields of this record from the given DBObject
  */
  def setFieldsFromDBObject(dbo: DBObject): Unit = meta.setFieldsFromDBObject(this, dbo)

  override def toString = {
    val fieldList = this.fields.map(f => "%s=%s" format (f.name,
        f.valueBox match {
          case Full(c: java.util.Calendar) => c.getTime().toString()
          case Full(null) => ""
          case Full(v) => v.toString
          case _ => ""
        }))

    "%s={%s}" format (this.getClass.toString, fieldList.mkString(", "))
  }
}

/** Specialized MetaRecord that deals with BsonRecords */
trait BsonMetaRecord[BaseRecord <: BsonRecord[BaseRecord]] extends MetaRecord[BaseRecord] with JsonFormats {
  self: BaseRecord =>
  
  /**
  * Create a BasicDBObject from the field names and values.
  * - MongoFieldFlavor types (List) are converted to DBObjects
  *   using asDBObject
  */
  def asDBObject(inst: BaseRecord): DBObject = {

    import Meta.Reflection._
    import field.MongoFieldFlavor

    val dbo = BasicDBObjectBuilder.start // use this so regex patterns can be stored.

    for (f <- fields(inst)) {
      f match {
        case field if (field.optional_? && field.valueBox.isEmpty) => // don't add to DBObject
        case field: EnumTypedField[Enumeration] =>
          field.asInstanceOf[EnumTypedField[Enumeration]].valueBox foreach {
            v => dbo.add(mongoName(f), v.id)
          }
        case field: EnumNameTypedField[Enumeration] =>
          field.asInstanceOf[EnumNameTypedField[Enumeration]].valueBox foreach {
            v => dbo.add(mongoName(f), v.toString)
          }
        case field: MongoFieldFlavor[Any] =>
          dbo.add(mongoName(f), field.asInstanceOf[MongoFieldFlavor[Any]].asDBObject)
        case field => field.valueBox foreach (_.asInstanceOf[AnyRef] match {
          case null => dbo.add(mongoName(f), null)
          case x if primitive_?(x.getClass) => dbo.add(mongoName(f), x)
          case x if mongotype_?(x.getClass) => dbo.add(mongoName(f), x)
          case x if datetype_?(x.getClass) => dbo.add(mongoName(f), datetype2dbovalue(x))
          case x: BsonRecord[_] => dbo.add(mongoName(f), x.asDBObject)
          case o => dbo.add(mongoName(f), o.toString)
        })
      }
    }
    dbo.get
  }

  /**
  * Creates a new record, then sets the fields with the given DBObject.
  *
  * @param dbo - the DBObject
  * @return Box[BaseRecord]
  */
  def fromDBObject(dbo: DBObject): BaseRecord = {
    val inst: BaseRecord = createRecord
    setFieldsFromDBObject(inst, dbo)
    inst
  }

  /**
  * Populate the inst's fields with the values from a DBObject. Values are set
  * using setFromAny passing it the DBObject returned from Mongo.
  *
  * @param inst - the record that will be populated
  * @param obj - The DBObject
  * @return Box[BaseRecord]
  */
  def setFieldsFromDBObject(inst: BaseRecord, dbo: DBObject): Unit = {
    for {
      field <- inst.fields
      fieldName = mongoName(field)
      if (dbo.containsField(fieldName))
    } field.setFromAny(dbo.get(fieldName))

    inst.runSafe {
      inst.fields.foreach(_.resetDirty)
    }
  }

  /*
   * Return the name of the field in the encoded DBbject. If the field
   * implements MongoField and has overridden mongoName then
   * that will be used, otherwise the record field name.
   */
  def mongoName(field: Field[_, BaseRecord]): String = field match {
    case (mongoField: MongoField) => mongoField.mongoName openOr field.name
    case _ => field.name
  }
}