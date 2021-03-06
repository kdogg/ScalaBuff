package net.sandrogrzicic.scalabuff.compiler

import collection.mutable
import mutable.{ArrayBuffer, HashSet}

/**
 * Viktor Klang's Enum
 * Source: https://gist.github.com/1057513/
 */
trait Enum {

	import java.util.concurrent.atomic.AtomicReference

	type EnumVal <: Value

	private val _values = new AtomicReference(Vector[EnumVal]())

	/**
	 * Add an EnumVal to our storage, using CCAS to make sure it's thread safe, returns the ordinal.
	 */
	private final def addEnumVal(newVal: EnumVal): Int = {
		import _values.{get, compareAndSet => CAS}
		val oldVec = get
		val newVec = oldVec :+ newVal
		if ((get eq oldVec) && CAS(oldVec, newVec)) newVec.indexWhere(_ eq newVal) else addEnumVal(newVal)
	}

	/**
	 * Get all the enums that exist for this type.
	 */
	def values: Vector[EnumVal] = _values.get

	protected trait Value {
		self: EnumVal => // Enforce that no one mixes in Value in a non-EnumVal type
		final val ordinal = addEnumVal(this) // Adds the EnumVal and returns the ordinal

		def name: String

		override def toString = name
		override def equals(other: Any) = this eq other.asInstanceOf[AnyRef]
		override def hashCode = 31 * (this.getClass.## + name.## + ordinal)
	}

}

/**
 * Field labels.
 */
object FieldLabels extends Enum {
	sealed trait EnumVal extends Value

	val REQUIRED = new EnumVal { val name = "required" }
	val OPTIONAL = new EnumVal { val name = "optional" }
	val REPEATED = new EnumVal { val name = "repeated" }

	def apply(label: String) = values.find(label == _.name).getOrElse(
		throw new InvalidFieldLabelException(label)
	)

	class InvalidFieldLabelException(label: String) extends RuntimeException(
		"Invalid field label: " + label
	)
}

/**
 * Field types; both predefined and custom types are an instance of FieldTypes.EnumVal.
 */
object FieldTypes extends Enum {
	implicit def buffString(string: String): BuffedString = new BuffedString(string)

	/**
	 * Represents a predefined field type.
	 * @param name the field type name; only modified from inside this class.
	 * @param scalaType the output field type name; only modified from inside this class.
	 * @param defaultValue field type default value; only modified from inside this class.
	 * @param wireType the field wire type.
	 * @param isEnum whether the field is an Enum; only modified from inside this class.
	 * @param isMessage whether the field is a Message; only modified from inside this class.
	 */
	trait EnumVal extends Value {
		var name: String
		var scalaType: String
		var defaultValue: String
		var wireType: Int
		var isEnum: Boolean = false
		var isMessage: Boolean = false
	}

	/**
	 * A predefined field type; immutable.
	 */
	case class PredefinedEnumVal private[FieldTypes] (
		name: String, scalaType: String, defaultValue: String, wireType: Int
	) extends EnumVal {
		def name_=(name: String) {}
		def scalaType_=(scalaType: String) {}
		def defaultValue_=(defaultValue: String) {}
		def wireType_=(wireType: Int) {}
	}

	import com.google.protobuf.WireFormat._

	val INT32 = PredefinedEnumVal("Int32", "Int", "0", WIRETYPE_VARINT)
	val UINT32 = PredefinedEnumVal("UInt32", "Int", "0", WIRETYPE_VARINT)
	val SINT32 = PredefinedEnumVal("SInt32", "Int", "0", WIRETYPE_VARINT)
	val FIXED32 = PredefinedEnumVal("Fixed32", "Int", "0", WIRETYPE_FIXED32)
	val SFIXED32 = PredefinedEnumVal("SFixed32", "Int", "0", WIRETYPE_FIXED32)
	val INT64 = PredefinedEnumVal("Int64", "Long", "0L", WIRETYPE_VARINT)
	val UINT64 = PredefinedEnumVal("UInt64", "Long", "0L", WIRETYPE_VARINT)
	val SINT64 = PredefinedEnumVal("SInt64", "Long", "0L", WIRETYPE_VARINT)
	val FIXED64 = PredefinedEnumVal("Fixed64", "Long", "0L", WIRETYPE_FIXED64)
	val SFIXED64 = PredefinedEnumVal("SFixed64", "Long", "0L", WIRETYPE_FIXED64)
	val BOOL = PredefinedEnumVal("Bool", "Boolean", "false", WIRETYPE_VARINT)
	val FLOAT = PredefinedEnumVal("Float", "Float", "0.0f", WIRETYPE_FIXED32)
	val DOUBLE = PredefinedEnumVal("Double", "Double", "0.0", WIRETYPE_FIXED64)
	val BYTES = PredefinedEnumVal("Bytes", "com.google.protobuf.ByteString", "com.google.protobuf.ByteString.EMPTY", WIRETYPE_LENGTH_DELIMITED)
	val STRING = PredefinedEnumVal("String", "String", "\"\"", WIRETYPE_LENGTH_DELIMITED)

	/**
	 * A custom field type representing a Message or an Enum.
	 */
	case class CustomEnumVal private[FieldTypes] (
		var name: String, var scalaType: String, var defaultValue: String, var wireType: Int
	) extends EnumVal

	/**
	 * Returns an immutable FieldType.PredefinedEnumVal based on the specified proto field type,
	 * or a new EnumVal with a null default value if it's a custom Message or Enum type.
	 */
	def apply(fieldType: String) = {
		values find { fType =>
				fType.name.toLowerCase == fieldType.toLowerCase && fType.isInstanceOf[PredefinedEnumVal]
			} getOrElse	CustomEnumVal(fieldType, fieldType, "null", WIRETYPE_LENGTH_DELIMITED)
	}

	/**
	 * Modifies some fields of Message and Enum types so that they can be used properly.
	 * Discovers whether each field type is a Message or an Enum.
	 */
	def recognizeCustomTypes(tree: List[Node]) {
		val (enumNames, allProtoFields) = getEnumNames(tree)
		fixCustomTypes(tree, enumNames, allProtoFields)
		prependParentClassNames(tree)
	}

	/** Return all enum names and custom field types found in the specified tree. */
	protected def getEnumNames(
		tree: List[Node],
		enumNames: mutable.HashSet[String] = mutable.HashSet.empty[String],
		customFieldTypes: mutable.ArrayBuffer[FieldTypes.EnumVal] = mutable.ArrayBuffer.empty[FieldTypes.EnumVal]
	): (HashSet[String], ArrayBuffer[EnumVal]) = {

		for (node <- tree) {
			node match {
				case Message(name, body) =>
					enumNames ++= body.enums.map(_.name)
					customFieldTypes ++= body.fields.map(_.fType) collect { case t: CustomEnumVal => t }
					getEnumNames(body.messages, enumNames, customFieldTypes)
				case EnumStatement(name, constants, options) => enumNames += name
				case _ =>
			}
		}
		(enumNames, customFieldTypes)
	}
	/** Update fields which have custom types. */
	protected def fixCustomTypes(tree: List[Node], enumNames: mutable.Set[String], allProtoFields: mutable.Buffer[EnumVal]) {
		for (fType <- allProtoFields if !fType.isMessage && !fType.isEnum) {
			if (enumNames.contains(fType.name.dropUntilLast('.'))) {
				fType.isEnum = true
				fType.name = "Enum"
				fType.defaultValue = fType.scalaType + "._UNINITIALIZED"
				fType.scalaType += ".EnumVal"
				fType.wireType = WIRETYPE_VARINT
			} else {
				fType.isMessage = true
				fType.name = "Message"
				fType.defaultValue = fType.scalaType + ".defaultInstance"
			}
		}
	}

	/** Prepend parent class names to all nested custom field types. */
	protected def prependParentClassNames(tree: List[Node]) {
		for (node <- tree) {
			node match {
				case Message(name, body) =>
					// prepend parent class names to all nested enums
					body.enums.foreach {
						case EnumStatement(eName, eConstants, eOptions) => {
							body.fields.withFilter(_.fType.isEnum).foreach { field =>
								val fType = field.fType
								fType.scalaType = name + "." + fType.scalaType
								fType.defaultValue = fType.scalaType.replace(".EnumVal", "") + "._UNINITIALIZED"
							}
						}
					}
					// prepend parent class names to all messages
					body.messages.foreach {
						case Message(mName, mBody) => {
							body.fields.withFilter(_.fType.isMessage).foreach { field =>
								val fType = field.fType
								fType.scalaType = name + "." + fType.scalaType
								fType.defaultValue = name + "." + fType.defaultValue
							}
							// recurse for any nested messages
							prependParentClassNames(mBody.messages)
						}
					}
				case _ =>
			}
		}
	}

}
