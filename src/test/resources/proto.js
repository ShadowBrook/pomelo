/*eslint-disable block-scoped-var, id-length, no-control-regex, no-magic-numbers, no-prototype-builtins, no-redeclare, no-shadow, no-var, sort-vars*/
import * as $protobuf from "protobufjs/minimal";

// Common aliases
const $Reader = $protobuf.Reader, $Writer = $protobuf.Writer, $util = $protobuf.util;

// Exported root namespace
const $root = $protobuf.roots["default"] || ($protobuf.roots["default"] = {});

export const im = $root.im = (() => {

    /**
     * Namespace im.
     * @exports im
     * @namespace
     */
    const im = {};

    im.ack = (function() {

        /**
         * Namespace ack.
         * @memberof im
         * @namespace
         */
        const ack = {};

        ack.AckReq = (function() {

            /**
             * Properties of an AckReq.
             * @memberof im.ack
             * @interface IAckReq
             * @property {Array.<number|Long>|null} [messageIds] AckReq messageIds
             * @property {im.common.AckType|null} [ackType] AckReq ackType
             */

            /**
             * Constructs a new AckReq.
             * @memberof im.ack
             * @classdesc Represents an AckReq.
             * @implements IAckReq
             * @constructor
             * @param {im.ack.IAckReq=} [properties] Properties to set
             */
            function AckReq(properties) {
                this.messageIds = [];
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * AckReq messageIds.
             * @member {Array.<number|Long>} messageIds
             * @memberof im.ack.AckReq
             * @instance
             */
            AckReq.prototype.messageIds = $util.emptyArray;

            /**
             * AckReq ackType.
             * @member {im.common.AckType} ackType
             * @memberof im.ack.AckReq
             * @instance
             */
            AckReq.prototype.ackType = 0;

            /**
             * Creates a new AckReq instance using the specified properties.
             * @function create
             * @memberof im.ack.AckReq
             * @static
             * @param {im.ack.IAckReq=} [properties] Properties to set
             * @returns {im.ack.AckReq} AckReq instance
             */
            AckReq.create = function create(properties) {
                return new AckReq(properties);
            };

            /**
             * Encodes the specified AckReq message. Does not implicitly {@link im.ack.AckReq.verify|verify} messages.
             * @function encode
             * @memberof im.ack.AckReq
             * @static
             * @param {im.ack.IAckReq} message AckReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AckReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.messageIds != null && message.messageIds.length) {
                    writer.uint32(/* id 1, wireType 2 =*/10).fork();
                    for (let i = 0; i < message.messageIds.length; ++i)
                        writer.int64(message.messageIds[i]);
                    writer.ldelim();
                }
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    writer.uint32(/* id 2, wireType 0 =*/16).int32(message.ackType);
                return writer;
            };

            /**
             * Encodes the specified AckReq message, length delimited. Does not implicitly {@link im.ack.AckReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.ack.AckReq
             * @static
             * @param {im.ack.IAckReq} message AckReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AckReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes an AckReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.ack.AckReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.ack.AckReq} AckReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AckReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.ack.AckReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            if (!(message.messageIds && message.messageIds.length))
                                message.messageIds = [];
                            if ((tag & 7) === 2) {
                                let end2 = reader.uint32() + reader.pos;
                                while (reader.pos < end2)
                                    message.messageIds.push(reader.int64());
                            } else
                                message.messageIds.push(reader.int64());
                            break;
                        }
                    case 2: {
                            message.ackType = reader.int32();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes an AckReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.ack.AckReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.ack.AckReq} AckReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AckReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies an AckReq message.
             * @function verify
             * @memberof im.ack.AckReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            AckReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.messageIds != null && Object.hasOwnProperty.call(message, "messageIds")) {
                    if (!Array.isArray(message.messageIds))
                        return "messageIds: array expected";
                    for (let i = 0; i < message.messageIds.length; ++i)
                        if (!$util.isInteger(message.messageIds[i]) && !(message.messageIds[i] && $util.isInteger(message.messageIds[i].low) && $util.isInteger(message.messageIds[i].high)))
                            return "messageIds: integer|Long[] expected";
                }
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    switch (message.ackType) {
                    default:
                        return "ackType: enum value expected";
                    case 0:
                    case 1:
                        break;
                    }
                return null;
            };

            /**
             * Creates an AckReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.ack.AckReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.ack.AckReq} AckReq
             */
            AckReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.ack.AckReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.ack.AckReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.ack.AckReq();
                if (object.messageIds) {
                    if (!Array.isArray(object.messageIds))
                        throw TypeError(".im.ack.AckReq.messageIds: array expected");
                    message.messageIds = [];
                    for (let i = 0; i < object.messageIds.length; ++i)
                        if ($util.Long)
                            message.messageIds[i] = $util.Long.fromValue(object.messageIds[i], false);
                        else if (typeof object.messageIds[i] === "string")
                            message.messageIds[i] = parseInt(object.messageIds[i], 10);
                        else if (typeof object.messageIds[i] === "number")
                            message.messageIds[i] = object.messageIds[i];
                        else if (typeof object.messageIds[i] === "object")
                            message.messageIds[i] = new $util.LongBits(object.messageIds[i].low >>> 0, object.messageIds[i].high >>> 0).toNumber();
                }
                switch (object.ackType) {
                default:
                    if (typeof object.ackType === "number") {
                        message.ackType = object.ackType;
                        break;
                    }
                    break;
                case "RECEIVED":
                case 0:
                    message.ackType = 0;
                    break;
                case "SEEN":
                case 1:
                    message.ackType = 1;
                    break;
                }
                return message;
            };

            /**
             * Creates a plain object from an AckReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.ack.AckReq
             * @static
             * @param {im.ack.AckReq} message AckReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            AckReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.arrays || options.defaults)
                    object.messageIds = [];
                if (options.defaults)
                    object.ackType = options.enums === String ? "RECEIVED" : 0;
                if (message.messageIds && message.messageIds.length) {
                    object.messageIds = [];
                    for (let j = 0; j < message.messageIds.length; ++j)
                        if (typeof BigInt !== "undefined" && options.longs === BigInt)
                            object.messageIds[j] = typeof message.messageIds[j] === "number" ? BigInt(message.messageIds[j]) : $util.Long.fromBits(message.messageIds[j].low >>> 0, message.messageIds[j].high >>> 0, false).toBigInt();
                        else if (typeof message.messageIds[j] === "number")
                            object.messageIds[j] = options.longs === String ? String(message.messageIds[j]) : message.messageIds[j];
                        else
                            object.messageIds[j] = options.longs === String ? $util.Long.prototype.toString.call(message.messageIds[j]) : options.longs === Number ? new $util.LongBits(message.messageIds[j].low >>> 0, message.messageIds[j].high >>> 0).toNumber() : message.messageIds[j];
                }
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    object.ackType = options.enums === String ? $root.im.common.AckType[message.ackType] === undefined ? message.ackType : $root.im.common.AckType[message.ackType] : message.ackType;
                return object;
            };

            /**
             * Converts this AckReq to JSON.
             * @function toJSON
             * @memberof im.ack.AckReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            AckReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for AckReq
             * @function getTypeUrl
             * @memberof im.ack.AckReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            AckReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.ack.AckReq";
            };

            return AckReq;
        })();

        ack.AckResp = (function() {

            /**
             * Properties of an AckResp.
             * @memberof im.ack
             * @interface IAckResp
             * @property {im.common.AckType|null} [ackType] AckResp ackType
             */

            /**
             * Constructs a new AckResp.
             * @memberof im.ack
             * @classdesc Represents an AckResp.
             * @implements IAckResp
             * @constructor
             * @param {im.ack.IAckResp=} [properties] Properties to set
             */
            function AckResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * AckResp ackType.
             * @member {im.common.AckType} ackType
             * @memberof im.ack.AckResp
             * @instance
             */
            AckResp.prototype.ackType = 0;

            /**
             * Creates a new AckResp instance using the specified properties.
             * @function create
             * @memberof im.ack.AckResp
             * @static
             * @param {im.ack.IAckResp=} [properties] Properties to set
             * @returns {im.ack.AckResp} AckResp instance
             */
            AckResp.create = function create(properties) {
                return new AckResp(properties);
            };

            /**
             * Encodes the specified AckResp message. Does not implicitly {@link im.ack.AckResp.verify|verify} messages.
             * @function encode
             * @memberof im.ack.AckResp
             * @static
             * @param {im.ack.IAckResp} message AckResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AckResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.ackType);
                return writer;
            };

            /**
             * Encodes the specified AckResp message, length delimited. Does not implicitly {@link im.ack.AckResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.ack.AckResp
             * @static
             * @param {im.ack.IAckResp} message AckResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AckResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes an AckResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.ack.AckResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.ack.AckResp} AckResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AckResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.ack.AckResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.ackType = reader.int32();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes an AckResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.ack.AckResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.ack.AckResp} AckResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AckResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies an AckResp message.
             * @function verify
             * @memberof im.ack.AckResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            AckResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    switch (message.ackType) {
                    default:
                        return "ackType: enum value expected";
                    case 0:
                    case 1:
                        break;
                    }
                return null;
            };

            /**
             * Creates an AckResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.ack.AckResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.ack.AckResp} AckResp
             */
            AckResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.ack.AckResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.ack.AckResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.ack.AckResp();
                switch (object.ackType) {
                default:
                    if (typeof object.ackType === "number") {
                        message.ackType = object.ackType;
                        break;
                    }
                    break;
                case "RECEIVED":
                case 0:
                    message.ackType = 0;
                    break;
                case "SEEN":
                case 1:
                    message.ackType = 1;
                    break;
                }
                return message;
            };

            /**
             * Creates a plain object from an AckResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.ack.AckResp
             * @static
             * @param {im.ack.AckResp} message AckResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            AckResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults)
                    object.ackType = options.enums === String ? "RECEIVED" : 0;
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    object.ackType = options.enums === String ? $root.im.common.AckType[message.ackType] === undefined ? message.ackType : $root.im.common.AckType[message.ackType] : message.ackType;
                return object;
            };

            /**
             * Converts this AckResp to JSON.
             * @function toJSON
             * @memberof im.ack.AckResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            AckResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for AckResp
             * @function getTypeUrl
             * @memberof im.ack.AckResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            AckResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.ack.AckResp";
            };

            return AckResp;
        })();

        ack.AckNotify = (function() {

            /**
             * Properties of an AckNotify.
             * @memberof im.ack
             * @interface IAckNotify
             * @property {Array.<number|Long>|null} [messageIds] AckNotify messageIds
             * @property {im.common.AckType|null} [ackType] AckNotify ackType
             */

            /**
             * Constructs a new AckNotify.
             * @memberof im.ack
             * @classdesc Represents an AckNotify.
             * @implements IAckNotify
             * @constructor
             * @param {im.ack.IAckNotify=} [properties] Properties to set
             */
            function AckNotify(properties) {
                this.messageIds = [];
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * AckNotify messageIds.
             * @member {Array.<number|Long>} messageIds
             * @memberof im.ack.AckNotify
             * @instance
             */
            AckNotify.prototype.messageIds = $util.emptyArray;

            /**
             * AckNotify ackType.
             * @member {im.common.AckType} ackType
             * @memberof im.ack.AckNotify
             * @instance
             */
            AckNotify.prototype.ackType = 0;

            /**
             * Creates a new AckNotify instance using the specified properties.
             * @function create
             * @memberof im.ack.AckNotify
             * @static
             * @param {im.ack.IAckNotify=} [properties] Properties to set
             * @returns {im.ack.AckNotify} AckNotify instance
             */
            AckNotify.create = function create(properties) {
                return new AckNotify(properties);
            };

            /**
             * Encodes the specified AckNotify message. Does not implicitly {@link im.ack.AckNotify.verify|verify} messages.
             * @function encode
             * @memberof im.ack.AckNotify
             * @static
             * @param {im.ack.IAckNotify} message AckNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AckNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.messageIds != null && message.messageIds.length) {
                    writer.uint32(/* id 1, wireType 2 =*/10).fork();
                    for (let i = 0; i < message.messageIds.length; ++i)
                        writer.int64(message.messageIds[i]);
                    writer.ldelim();
                }
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    writer.uint32(/* id 2, wireType 0 =*/16).int32(message.ackType);
                return writer;
            };

            /**
             * Encodes the specified AckNotify message, length delimited. Does not implicitly {@link im.ack.AckNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.ack.AckNotify
             * @static
             * @param {im.ack.IAckNotify} message AckNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AckNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes an AckNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.ack.AckNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.ack.AckNotify} AckNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AckNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.ack.AckNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            if (!(message.messageIds && message.messageIds.length))
                                message.messageIds = [];
                            if ((tag & 7) === 2) {
                                let end2 = reader.uint32() + reader.pos;
                                while (reader.pos < end2)
                                    message.messageIds.push(reader.int64());
                            } else
                                message.messageIds.push(reader.int64());
                            break;
                        }
                    case 2: {
                            message.ackType = reader.int32();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes an AckNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.ack.AckNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.ack.AckNotify} AckNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AckNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies an AckNotify message.
             * @function verify
             * @memberof im.ack.AckNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            AckNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.messageIds != null && Object.hasOwnProperty.call(message, "messageIds")) {
                    if (!Array.isArray(message.messageIds))
                        return "messageIds: array expected";
                    for (let i = 0; i < message.messageIds.length; ++i)
                        if (!$util.isInteger(message.messageIds[i]) && !(message.messageIds[i] && $util.isInteger(message.messageIds[i].low) && $util.isInteger(message.messageIds[i].high)))
                            return "messageIds: integer|Long[] expected";
                }
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    switch (message.ackType) {
                    default:
                        return "ackType: enum value expected";
                    case 0:
                    case 1:
                        break;
                    }
                return null;
            };

            /**
             * Creates an AckNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.ack.AckNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.ack.AckNotify} AckNotify
             */
            AckNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.ack.AckNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.ack.AckNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.ack.AckNotify();
                if (object.messageIds) {
                    if (!Array.isArray(object.messageIds))
                        throw TypeError(".im.ack.AckNotify.messageIds: array expected");
                    message.messageIds = [];
                    for (let i = 0; i < object.messageIds.length; ++i)
                        if ($util.Long)
                            message.messageIds[i] = $util.Long.fromValue(object.messageIds[i], false);
                        else if (typeof object.messageIds[i] === "string")
                            message.messageIds[i] = parseInt(object.messageIds[i], 10);
                        else if (typeof object.messageIds[i] === "number")
                            message.messageIds[i] = object.messageIds[i];
                        else if (typeof object.messageIds[i] === "object")
                            message.messageIds[i] = new $util.LongBits(object.messageIds[i].low >>> 0, object.messageIds[i].high >>> 0).toNumber();
                }
                switch (object.ackType) {
                default:
                    if (typeof object.ackType === "number") {
                        message.ackType = object.ackType;
                        break;
                    }
                    break;
                case "RECEIVED":
                case 0:
                    message.ackType = 0;
                    break;
                case "SEEN":
                case 1:
                    message.ackType = 1;
                    break;
                }
                return message;
            };

            /**
             * Creates a plain object from an AckNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.ack.AckNotify
             * @static
             * @param {im.ack.AckNotify} message AckNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            AckNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.arrays || options.defaults)
                    object.messageIds = [];
                if (options.defaults)
                    object.ackType = options.enums === String ? "RECEIVED" : 0;
                if (message.messageIds && message.messageIds.length) {
                    object.messageIds = [];
                    for (let j = 0; j < message.messageIds.length; ++j)
                        if (typeof BigInt !== "undefined" && options.longs === BigInt)
                            object.messageIds[j] = typeof message.messageIds[j] === "number" ? BigInt(message.messageIds[j]) : $util.Long.fromBits(message.messageIds[j].low >>> 0, message.messageIds[j].high >>> 0, false).toBigInt();
                        else if (typeof message.messageIds[j] === "number")
                            object.messageIds[j] = options.longs === String ? String(message.messageIds[j]) : message.messageIds[j];
                        else
                            object.messageIds[j] = options.longs === String ? $util.Long.prototype.toString.call(message.messageIds[j]) : options.longs === Number ? new $util.LongBits(message.messageIds[j].low >>> 0, message.messageIds[j].high >>> 0).toNumber() : message.messageIds[j];
                }
                if (message.ackType != null && Object.hasOwnProperty.call(message, "ackType"))
                    object.ackType = options.enums === String ? $root.im.common.AckType[message.ackType] === undefined ? message.ackType : $root.im.common.AckType[message.ackType] : message.ackType;
                return object;
            };

            /**
             * Converts this AckNotify to JSON.
             * @function toJSON
             * @memberof im.ack.AckNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            AckNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for AckNotify
             * @function getTypeUrl
             * @memberof im.ack.AckNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            AckNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.ack.AckNotify";
            };

            return AckNotify;
        })();

        return ack;
    })();

    im.common = (function() {

        /**
         * Namespace common.
         * @memberof im
         * @namespace
         */
        const common = {};

        /**
         * Cmd enum.
         * @name im.common.Cmd
         * @enum {number}
         * @property {number} CMD_UNKNOWN=0 CMD_UNKNOWN value
         * @property {number} CMD_AUTH_REQ=1 CMD_AUTH_REQ value
         * @property {number} CMD_AUTH_RESP=2 CMD_AUTH_RESP value
         * @property {number} CMD_LOGOUT_REQ=3 CMD_LOGOUT_REQ value
         * @property {number} CMD_LOGOUT_RESP=4 CMD_LOGOUT_RESP value
         * @property {number} CMD_C2C_REQ=16 CMD_C2C_REQ value
         * @property {number} CMD_C2C_RESP=17 CMD_C2C_RESP value
         * @property {number} CMD_C2C_NOTIFY=18 CMD_C2C_NOTIFY value
         * @property {number} CMD_C2G_REQ=32 CMD_C2G_REQ value
         * @property {number} CMD_C2G_RESP=33 CMD_C2G_RESP value
         * @property {number} CMD_C2G_NOTIFY=34 CMD_C2G_NOTIFY value
         * @property {number} CMD_PULL_REQ=48 CMD_PULL_REQ value
         * @property {number} CMD_PULL_RESP=49 CMD_PULL_RESP value
         * @property {number} CMD_CTRL_REQ=64 CMD_CTRL_REQ value
         * @property {number} CMD_CTRL_RESP=65 CMD_CTRL_RESP value
         * @property {number} CMD_CTRL_NOTIFY=66 CMD_CTRL_NOTIFY value
         * @property {number} CMD_PING=80 CMD_PING value
         * @property {number} CMD_PONG=81 CMD_PONG value
         * @property {number} CMD_ACK_REQ=82 CMD_ACK_REQ value
         * @property {number} CMD_ACK_RESP=83 CMD_ACK_RESP value
         * @property {number} CMD_ACK_NOTIFY=84 CMD_ACK_NOTIFY value
         * @property {number} CMD_FRIEND_SEARCH_REQ=96 CMD_FRIEND_SEARCH_REQ value
         * @property {number} CMD_FRIEND_SEARCH_RESP=97 CMD_FRIEND_SEARCH_RESP value
         * @property {number} CMD_FRIEND_ADD_REQ=98 CMD_FRIEND_ADD_REQ value
         * @property {number} CMD_FRIEND_ADD_RESP=99 CMD_FRIEND_ADD_RESP value
         * @property {number} CMD_FRIEND_ADD_NOTIFY=100 CMD_FRIEND_ADD_NOTIFY value
         * @property {number} CMD_FRIEND_ACCEPT_REQ=101 CMD_FRIEND_ACCEPT_REQ value
         * @property {number} CMD_FRIEND_ACCEPT_RESP=102 CMD_FRIEND_ACCEPT_RESP value
         * @property {number} CMD_FRIEND_ACCEPT_NOTIFY=103 CMD_FRIEND_ACCEPT_NOTIFY value
         * @property {number} CMD_FRIEND_DELETE_REQ=104 CMD_FRIEND_DELETE_REQ value
         * @property {number} CMD_FRIEND_DELETE_RESP=105 CMD_FRIEND_DELETE_RESP value
         * @property {number} CMD_FRIEND_DELETE_NOTIFY=106 CMD_FRIEND_DELETE_NOTIFY value
         * @property {number} CMD_ERROR=65535 CMD_ERROR value
         */
        common.Cmd = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "CMD_UNKNOWN"] = 0;
            values[valuesById[1] = "CMD_AUTH_REQ"] = 1;
            values[valuesById[2] = "CMD_AUTH_RESP"] = 2;
            values[valuesById[3] = "CMD_LOGOUT_REQ"] = 3;
            values[valuesById[4] = "CMD_LOGOUT_RESP"] = 4;
            values[valuesById[16] = "CMD_C2C_REQ"] = 16;
            values[valuesById[17] = "CMD_C2C_RESP"] = 17;
            values[valuesById[18] = "CMD_C2C_NOTIFY"] = 18;
            values[valuesById[32] = "CMD_C2G_REQ"] = 32;
            values[valuesById[33] = "CMD_C2G_RESP"] = 33;
            values[valuesById[34] = "CMD_C2G_NOTIFY"] = 34;
            values[valuesById[48] = "CMD_PULL_REQ"] = 48;
            values[valuesById[49] = "CMD_PULL_RESP"] = 49;
            values[valuesById[64] = "CMD_CTRL_REQ"] = 64;
            values[valuesById[65] = "CMD_CTRL_RESP"] = 65;
            values[valuesById[66] = "CMD_CTRL_NOTIFY"] = 66;
            values[valuesById[80] = "CMD_PING"] = 80;
            values[valuesById[81] = "CMD_PONG"] = 81;
            values[valuesById[82] = "CMD_ACK_REQ"] = 82;
            values[valuesById[83] = "CMD_ACK_RESP"] = 83;
            values[valuesById[84] = "CMD_ACK_NOTIFY"] = 84;
            values[valuesById[96] = "CMD_FRIEND_SEARCH_REQ"] = 96;
            values[valuesById[97] = "CMD_FRIEND_SEARCH_RESP"] = 97;
            values[valuesById[98] = "CMD_FRIEND_ADD_REQ"] = 98;
            values[valuesById[99] = "CMD_FRIEND_ADD_RESP"] = 99;
            values[valuesById[100] = "CMD_FRIEND_ADD_NOTIFY"] = 100;
            values[valuesById[101] = "CMD_FRIEND_ACCEPT_REQ"] = 101;
            values[valuesById[102] = "CMD_FRIEND_ACCEPT_RESP"] = 102;
            values[valuesById[103] = "CMD_FRIEND_ACCEPT_NOTIFY"] = 103;
            values[valuesById[104] = "CMD_FRIEND_DELETE_REQ"] = 104;
            values[valuesById[105] = "CMD_FRIEND_DELETE_RESP"] = 105;
            values[valuesById[106] = "CMD_FRIEND_DELETE_NOTIFY"] = 106;
            values[valuesById[65535] = "CMD_ERROR"] = 65535;
            return values;
        })();

        /**
         * MsgType enum.
         * @name im.common.MsgType
         * @enum {number}
         * @property {number} MSG_TYPE_UNKNOWN=0 MSG_TYPE_UNKNOWN value
         * @property {number} MSG_TYPE_TEXT=1 MSG_TYPE_TEXT value
         * @property {number} MSG_TYPE_IMAGE=2 MSG_TYPE_IMAGE value
         * @property {number} MSG_TYPE_VOICE=3 MSG_TYPE_VOICE value
         * @property {number} MSG_TYPE_VIDEO=4 MSG_TYPE_VIDEO value
         * @property {number} MSG_TYPE_FILE=5 MSG_TYPE_FILE value
         * @property {number} MSG_TYPE_EMOJI=6 MSG_TYPE_EMOJI value
         * @property {number} MSG_TYPE_SYSTEM=7 MSG_TYPE_SYSTEM value
         */
        common.MsgType = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "MSG_TYPE_UNKNOWN"] = 0;
            values[valuesById[1] = "MSG_TYPE_TEXT"] = 1;
            values[valuesById[2] = "MSG_TYPE_IMAGE"] = 2;
            values[valuesById[3] = "MSG_TYPE_VOICE"] = 3;
            values[valuesById[4] = "MSG_TYPE_VIDEO"] = 4;
            values[valuesById[5] = "MSG_TYPE_FILE"] = 5;
            values[valuesById[6] = "MSG_TYPE_EMOJI"] = 6;
            values[valuesById[7] = "MSG_TYPE_SYSTEM"] = 7;
            return values;
        })();

        /**
         * AckType enum.
         * @name im.common.AckType
         * @enum {number}
         * @property {number} RECEIVED=0 RECEIVED value
         * @property {number} SEEN=1 SEEN value
         */
        common.AckType = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "RECEIVED"] = 0;
            values[valuesById[1] = "SEEN"] = 1;
            return values;
        })();

        common.MessageContent = (function() {

            /**
             * Properties of a MessageContent.
             * @memberof im.common
             * @interface IMessageContent
             * @property {im.common.MsgType|null} [msgType] MessageContent msgType
             * @property {Uint8Array|null} [content] MessageContent content
             * @property {number|Long|null} [timestamp] MessageContent timestamp
             * @property {Object.<string,string>|null} [ext] MessageContent ext
             */

            /**
             * Constructs a new MessageContent.
             * @memberof im.common
             * @classdesc Represents a MessageContent.
             * @implements IMessageContent
             * @constructor
             * @param {im.common.IMessageContent=} [properties] Properties to set
             */
            function MessageContent(properties) {
                this.ext = {};
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * MessageContent msgType.
             * @member {im.common.MsgType} msgType
             * @memberof im.common.MessageContent
             * @instance
             */
            MessageContent.prototype.msgType = 0;

            /**
             * MessageContent content.
             * @member {Uint8Array} content
             * @memberof im.common.MessageContent
             * @instance
             */
            MessageContent.prototype.content = $util.newBuffer([]);

            /**
             * MessageContent timestamp.
             * @member {number|Long} timestamp
             * @memberof im.common.MessageContent
             * @instance
             */
            MessageContent.prototype.timestamp = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * MessageContent ext.
             * @member {Object.<string,string>} ext
             * @memberof im.common.MessageContent
             * @instance
             */
            MessageContent.prototype.ext = $util.emptyObject;

            /**
             * Creates a new MessageContent instance using the specified properties.
             * @function create
             * @memberof im.common.MessageContent
             * @static
             * @param {im.common.IMessageContent=} [properties] Properties to set
             * @returns {im.common.MessageContent} MessageContent instance
             */
            MessageContent.create = function create(properties) {
                return new MessageContent(properties);
            };

            /**
             * Encodes the specified MessageContent message. Does not implicitly {@link im.common.MessageContent.verify|verify} messages.
             * @function encode
             * @memberof im.common.MessageContent
             * @static
             * @param {im.common.IMessageContent} message MessageContent message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            MessageContent.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.msgType != null && Object.hasOwnProperty.call(message, "msgType"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.msgType);
                if (message.content != null && Object.hasOwnProperty.call(message, "content"))
                    writer.uint32(/* id 2, wireType 2 =*/18).bytes(message.content);
                if (message.timestamp != null && Object.hasOwnProperty.call(message, "timestamp"))
                    writer.uint32(/* id 3, wireType 0 =*/24).int64(message.timestamp);
                if (message.ext != null && Object.hasOwnProperty.call(message, "ext"))
                    for (let keys = Object.keys(message.ext), i = 0; i < keys.length; ++i)
                        writer.uint32(/* id 4, wireType 2 =*/34).fork().uint32(/* id 1, wireType 2 =*/10).string(keys[i]).uint32(/* id 2, wireType 2 =*/18).string(message.ext[keys[i]]).ldelim();
                return writer;
            };

            /**
             * Encodes the specified MessageContent message, length delimited. Does not implicitly {@link im.common.MessageContent.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.common.MessageContent
             * @static
             * @param {im.common.IMessageContent} message MessageContent message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            MessageContent.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a MessageContent message from the specified reader or buffer.
             * @function decode
             * @memberof im.common.MessageContent
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.common.MessageContent} MessageContent
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            MessageContent.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.common.MessageContent(), key, value;
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.msgType = reader.int32();
                            break;
                        }
                    case 2: {
                            message.content = reader.bytes();
                            break;
                        }
                    case 3: {
                            message.timestamp = reader.int64();
                            break;
                        }
                    case 4: {
                            if (message.ext === $util.emptyObject)
                                message.ext = {};
                            let end2 = reader.uint32() + reader.pos;
                            key = "";
                            value = "";
                            while (reader.pos < end2) {
                                let tag2 = reader.uint32();
                                switch (tag2 >>> 3) {
                                case 1:
                                    key = reader.string();
                                    break;
                                case 2:
                                    value = reader.string();
                                    break;
                                default:
                                    reader.skipType(tag2 & 7, long);
                                    break;
                                }
                            }
                            if (key === "__proto__")
                                $util.makeProp(message.ext, key);
                            message.ext[key] = value;
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a MessageContent message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.common.MessageContent
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.common.MessageContent} MessageContent
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            MessageContent.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a MessageContent message.
             * @function verify
             * @memberof im.common.MessageContent
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            MessageContent.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.msgType != null && Object.hasOwnProperty.call(message, "msgType"))
                    switch (message.msgType) {
                    default:
                        return "msgType: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                    case 7:
                        break;
                    }
                if (message.content != null && Object.hasOwnProperty.call(message, "content"))
                    if (!(message.content && typeof message.content.length === "number" || $util.isString(message.content)))
                        return "content: buffer expected";
                if (message.timestamp != null && Object.hasOwnProperty.call(message, "timestamp"))
                    if (!$util.isInteger(message.timestamp) && !(message.timestamp && $util.isInteger(message.timestamp.low) && $util.isInteger(message.timestamp.high)))
                        return "timestamp: integer|Long expected";
                if (message.ext != null && Object.hasOwnProperty.call(message, "ext")) {
                    if (!$util.isObject(message.ext))
                        return "ext: object expected";
                    let key = Object.keys(message.ext);
                    for (let i = 0; i < key.length; ++i)
                        if (!$util.isString(message.ext[key[i]]))
                            return "ext: string{k:string} expected";
                }
                return null;
            };

            /**
             * Creates a MessageContent message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.common.MessageContent
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.common.MessageContent} MessageContent
             */
            MessageContent.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.common.MessageContent)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.common.MessageContent: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.common.MessageContent();
                switch (object.msgType) {
                default:
                    if (typeof object.msgType === "number") {
                        message.msgType = object.msgType;
                        break;
                    }
                    break;
                case "MSG_TYPE_UNKNOWN":
                case 0:
                    message.msgType = 0;
                    break;
                case "MSG_TYPE_TEXT":
                case 1:
                    message.msgType = 1;
                    break;
                case "MSG_TYPE_IMAGE":
                case 2:
                    message.msgType = 2;
                    break;
                case "MSG_TYPE_VOICE":
                case 3:
                    message.msgType = 3;
                    break;
                case "MSG_TYPE_VIDEO":
                case 4:
                    message.msgType = 4;
                    break;
                case "MSG_TYPE_FILE":
                case 5:
                    message.msgType = 5;
                    break;
                case "MSG_TYPE_EMOJI":
                case 6:
                    message.msgType = 6;
                    break;
                case "MSG_TYPE_SYSTEM":
                case 7:
                    message.msgType = 7;
                    break;
                }
                if (object.content != null)
                    if (typeof object.content === "string")
                        $util.base64.decode(object.content, message.content = $util.newBuffer($util.base64.length(object.content)), 0);
                    else if (object.content.length >= 0)
                        message.content = object.content;
                if (object.timestamp != null)
                    if ($util.Long)
                        message.timestamp = $util.Long.fromValue(object.timestamp, false);
                    else if (typeof object.timestamp === "string")
                        message.timestamp = parseInt(object.timestamp, 10);
                    else if (typeof object.timestamp === "number")
                        message.timestamp = object.timestamp;
                    else if (typeof object.timestamp === "object")
                        message.timestamp = new $util.LongBits(object.timestamp.low >>> 0, object.timestamp.high >>> 0).toNumber();
                if (object.ext) {
                    if (!$util.isObject(object.ext))
                        throw TypeError(".im.common.MessageContent.ext: object expected");
                    message.ext = {};
                    for (let keys = Object.keys(object.ext), i = 0; i < keys.length; ++i) {
                        if (keys[i] === "__proto__")
                            $util.makeProp(message.ext, keys[i]);
                        message.ext[keys[i]] = String(object.ext[keys[i]]);
                    }
                }
                return message;
            };

            /**
             * Creates a plain object from a MessageContent message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.common.MessageContent
             * @static
             * @param {im.common.MessageContent} message MessageContent
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            MessageContent.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.objects || options.defaults)
                    object.ext = {};
                if (options.defaults) {
                    object.msgType = options.enums === String ? "MSG_TYPE_UNKNOWN" : 0;
                    if (options.bytes === String)
                        object.content = "";
                    else {
                        object.content = [];
                        if (options.bytes !== Array)
                            object.content = $util.newBuffer(object.content);
                    }
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.timestamp = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.timestamp = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                }
                if (message.msgType != null && Object.hasOwnProperty.call(message, "msgType"))
                    object.msgType = options.enums === String ? $root.im.common.MsgType[message.msgType] === undefined ? message.msgType : $root.im.common.MsgType[message.msgType] : message.msgType;
                if (message.content != null && Object.hasOwnProperty.call(message, "content"))
                    object.content = options.bytes === String ? $util.base64.encode(message.content, 0, message.content.length) : options.bytes === Array ? Array.prototype.slice.call(message.content) : message.content;
                if (message.timestamp != null && Object.hasOwnProperty.call(message, "timestamp"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.timestamp = typeof message.timestamp === "number" ? BigInt(message.timestamp) : $util.Long.fromBits(message.timestamp.low >>> 0, message.timestamp.high >>> 0, false).toBigInt();
                    else if (typeof message.timestamp === "number")
                        object.timestamp = options.longs === String ? String(message.timestamp) : message.timestamp;
                    else
                        object.timestamp = options.longs === String ? $util.Long.prototype.toString.call(message.timestamp) : options.longs === Number ? new $util.LongBits(message.timestamp.low >>> 0, message.timestamp.high >>> 0).toNumber() : message.timestamp;
                let keys2;
                if (message.ext && (keys2 = Object.keys(message.ext)).length) {
                    object.ext = {};
                    for (let j = 0; j < keys2.length; ++j) {
                        if (keys2[j] === "__proto__")
                            $util.makeProp(object.ext, keys2[j]);
                        object.ext[keys2[j]] = message.ext[keys2[j]];
                    }
                }
                return object;
            };

            /**
             * Converts this MessageContent to JSON.
             * @function toJSON
             * @memberof im.common.MessageContent
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            MessageContent.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for MessageContent
             * @function getTypeUrl
             * @memberof im.common.MessageContent
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            MessageContent.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.common.MessageContent";
            };

            return MessageContent;
        })();

        common.ErrorBody = (function() {

            /**
             * Properties of an ErrorBody.
             * @memberof im.common
             * @interface IErrorBody
             * @property {number|null} [code] ErrorBody code
             * @property {string|null} [message] ErrorBody message
             */

            /**
             * Constructs a new ErrorBody.
             * @memberof im.common
             * @classdesc Represents an ErrorBody.
             * @implements IErrorBody
             * @constructor
             * @param {im.common.IErrorBody=} [properties] Properties to set
             */
            function ErrorBody(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * ErrorBody code.
             * @member {number} code
             * @memberof im.common.ErrorBody
             * @instance
             */
            ErrorBody.prototype.code = 0;

            /**
             * ErrorBody message.
             * @member {string} message
             * @memberof im.common.ErrorBody
             * @instance
             */
            ErrorBody.prototype.message = "";

            /**
             * Creates a new ErrorBody instance using the specified properties.
             * @function create
             * @memberof im.common.ErrorBody
             * @static
             * @param {im.common.IErrorBody=} [properties] Properties to set
             * @returns {im.common.ErrorBody} ErrorBody instance
             */
            ErrorBody.create = function create(properties) {
                return new ErrorBody(properties);
            };

            /**
             * Encodes the specified ErrorBody message. Does not implicitly {@link im.common.ErrorBody.verify|verify} messages.
             * @function encode
             * @memberof im.common.ErrorBody
             * @static
             * @param {im.common.IErrorBody} message ErrorBody message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            ErrorBody.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                return writer;
            };

            /**
             * Encodes the specified ErrorBody message, length delimited. Does not implicitly {@link im.common.ErrorBody.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.common.ErrorBody
             * @static
             * @param {im.common.IErrorBody} message ErrorBody message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            ErrorBody.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes an ErrorBody message from the specified reader or buffer.
             * @function decode
             * @memberof im.common.ErrorBody
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.common.ErrorBody} ErrorBody
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            ErrorBody.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.common.ErrorBody();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes an ErrorBody message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.common.ErrorBody
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.common.ErrorBody} ErrorBody
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            ErrorBody.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies an ErrorBody message.
             * @function verify
             * @memberof im.common.ErrorBody
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            ErrorBody.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                return null;
            };

            /**
             * Creates an ErrorBody message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.common.ErrorBody
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.common.ErrorBody} ErrorBody
             */
            ErrorBody.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.common.ErrorBody)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.common.ErrorBody: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.common.ErrorBody();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                return message;
            };

            /**
             * Creates a plain object from an ErrorBody message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.common.ErrorBody
             * @static
             * @param {im.common.ErrorBody} message ErrorBody
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            ErrorBody.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                return object;
            };

            /**
             * Converts this ErrorBody to JSON.
             * @function toJSON
             * @memberof im.common.ErrorBody
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            ErrorBody.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for ErrorBody
             * @function getTypeUrl
             * @memberof im.common.ErrorBody
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            ErrorBody.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.common.ErrorBody";
            };

            return ErrorBody;
        })();

        return common;
    })();

    im.auth = (function() {

        /**
         * Namespace auth.
         * @memberof im
         * @namespace
         */
        const auth = {};

        auth.AuthReq = (function() {

            /**
             * Properties of an AuthReq.
             * @memberof im.auth
             * @interface IAuthReq
             * @property {string|null} [token] AuthReq token
             * @property {string|null} [deviceId] AuthReq deviceId
             * @property {string|null} [platform] AuthReq platform
             * @property {string|null} [appVersion] AuthReq appVersion
             */

            /**
             * Constructs a new AuthReq.
             * @memberof im.auth
             * @classdesc Represents an AuthReq.
             * @implements IAuthReq
             * @constructor
             * @param {im.auth.IAuthReq=} [properties] Properties to set
             */
            function AuthReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * AuthReq token.
             * @member {string} token
             * @memberof im.auth.AuthReq
             * @instance
             */
            AuthReq.prototype.token = "";

            /**
             * AuthReq deviceId.
             * @member {string} deviceId
             * @memberof im.auth.AuthReq
             * @instance
             */
            AuthReq.prototype.deviceId = "";

            /**
             * AuthReq platform.
             * @member {string} platform
             * @memberof im.auth.AuthReq
             * @instance
             */
            AuthReq.prototype.platform = "";

            /**
             * AuthReq appVersion.
             * @member {string} appVersion
             * @memberof im.auth.AuthReq
             * @instance
             */
            AuthReq.prototype.appVersion = "";

            /**
             * Creates a new AuthReq instance using the specified properties.
             * @function create
             * @memberof im.auth.AuthReq
             * @static
             * @param {im.auth.IAuthReq=} [properties] Properties to set
             * @returns {im.auth.AuthReq} AuthReq instance
             */
            AuthReq.create = function create(properties) {
                return new AuthReq(properties);
            };

            /**
             * Encodes the specified AuthReq message. Does not implicitly {@link im.auth.AuthReq.verify|verify} messages.
             * @function encode
             * @memberof im.auth.AuthReq
             * @static
             * @param {im.auth.IAuthReq} message AuthReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AuthReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.token != null && Object.hasOwnProperty.call(message, "token"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.token);
                if (message.deviceId != null && Object.hasOwnProperty.call(message, "deviceId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.deviceId);
                if (message.platform != null && Object.hasOwnProperty.call(message, "platform"))
                    writer.uint32(/* id 3, wireType 2 =*/26).string(message.platform);
                if (message.appVersion != null && Object.hasOwnProperty.call(message, "appVersion"))
                    writer.uint32(/* id 4, wireType 2 =*/34).string(message.appVersion);
                return writer;
            };

            /**
             * Encodes the specified AuthReq message, length delimited. Does not implicitly {@link im.auth.AuthReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.auth.AuthReq
             * @static
             * @param {im.auth.IAuthReq} message AuthReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AuthReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes an AuthReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.auth.AuthReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.auth.AuthReq} AuthReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AuthReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.auth.AuthReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.token = reader.string();
                            break;
                        }
                    case 2: {
                            message.deviceId = reader.string();
                            break;
                        }
                    case 3: {
                            message.platform = reader.string();
                            break;
                        }
                    case 4: {
                            message.appVersion = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes an AuthReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.auth.AuthReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.auth.AuthReq} AuthReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AuthReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies an AuthReq message.
             * @function verify
             * @memberof im.auth.AuthReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            AuthReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.token != null && Object.hasOwnProperty.call(message, "token"))
                    if (!$util.isString(message.token))
                        return "token: string expected";
                if (message.deviceId != null && Object.hasOwnProperty.call(message, "deviceId"))
                    if (!$util.isString(message.deviceId))
                        return "deviceId: string expected";
                if (message.platform != null && Object.hasOwnProperty.call(message, "platform"))
                    if (!$util.isString(message.platform))
                        return "platform: string expected";
                if (message.appVersion != null && Object.hasOwnProperty.call(message, "appVersion"))
                    if (!$util.isString(message.appVersion))
                        return "appVersion: string expected";
                return null;
            };

            /**
             * Creates an AuthReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.auth.AuthReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.auth.AuthReq} AuthReq
             */
            AuthReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.auth.AuthReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.auth.AuthReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.auth.AuthReq();
                if (object.token != null)
                    message.token = String(object.token);
                if (object.deviceId != null)
                    message.deviceId = String(object.deviceId);
                if (object.platform != null)
                    message.platform = String(object.platform);
                if (object.appVersion != null)
                    message.appVersion = String(object.appVersion);
                return message;
            };

            /**
             * Creates a plain object from an AuthReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.auth.AuthReq
             * @static
             * @param {im.auth.AuthReq} message AuthReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            AuthReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.token = "";
                    object.deviceId = "";
                    object.platform = "";
                    object.appVersion = "";
                }
                if (message.token != null && Object.hasOwnProperty.call(message, "token"))
                    object.token = message.token;
                if (message.deviceId != null && Object.hasOwnProperty.call(message, "deviceId"))
                    object.deviceId = message.deviceId;
                if (message.platform != null && Object.hasOwnProperty.call(message, "platform"))
                    object.platform = message.platform;
                if (message.appVersion != null && Object.hasOwnProperty.call(message, "appVersion"))
                    object.appVersion = message.appVersion;
                return object;
            };

            /**
             * Converts this AuthReq to JSON.
             * @function toJSON
             * @memberof im.auth.AuthReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            AuthReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for AuthReq
             * @function getTypeUrl
             * @memberof im.auth.AuthReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            AuthReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.auth.AuthReq";
            };

            return AuthReq;
        })();

        auth.AuthResp = (function() {

            /**
             * Properties of an AuthResp.
             * @memberof im.auth
             * @interface IAuthResp
             * @property {number|null} [code] AuthResp code
             * @property {string|null} [message] AuthResp message
             * @property {string|null} [userId] AuthResp userId
             * @property {number|Long|null} [expireAt] AuthResp expireAt
             */

            /**
             * Constructs a new AuthResp.
             * @memberof im.auth
             * @classdesc Represents an AuthResp.
             * @implements IAuthResp
             * @constructor
             * @param {im.auth.IAuthResp=} [properties] Properties to set
             */
            function AuthResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * AuthResp code.
             * @member {number} code
             * @memberof im.auth.AuthResp
             * @instance
             */
            AuthResp.prototype.code = 0;

            /**
             * AuthResp message.
             * @member {string} message
             * @memberof im.auth.AuthResp
             * @instance
             */
            AuthResp.prototype.message = "";

            /**
             * AuthResp userId.
             * @member {string} userId
             * @memberof im.auth.AuthResp
             * @instance
             */
            AuthResp.prototype.userId = "";

            /**
             * AuthResp expireAt.
             * @member {number|Long} expireAt
             * @memberof im.auth.AuthResp
             * @instance
             */
            AuthResp.prototype.expireAt = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * Creates a new AuthResp instance using the specified properties.
             * @function create
             * @memberof im.auth.AuthResp
             * @static
             * @param {im.auth.IAuthResp=} [properties] Properties to set
             * @returns {im.auth.AuthResp} AuthResp instance
             */
            AuthResp.create = function create(properties) {
                return new AuthResp(properties);
            };

            /**
             * Encodes the specified AuthResp message. Does not implicitly {@link im.auth.AuthResp.verify|verify} messages.
             * @function encode
             * @memberof im.auth.AuthResp
             * @static
             * @param {im.auth.IAuthResp} message AuthResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AuthResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 3, wireType 2 =*/26).string(message.userId);
                if (message.expireAt != null && Object.hasOwnProperty.call(message, "expireAt"))
                    writer.uint32(/* id 4, wireType 0 =*/32).int64(message.expireAt);
                return writer;
            };

            /**
             * Encodes the specified AuthResp message, length delimited. Does not implicitly {@link im.auth.AuthResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.auth.AuthResp
             * @static
             * @param {im.auth.IAuthResp} message AuthResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            AuthResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes an AuthResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.auth.AuthResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.auth.AuthResp} AuthResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AuthResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.auth.AuthResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    case 3: {
                            message.userId = reader.string();
                            break;
                        }
                    case 4: {
                            message.expireAt = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes an AuthResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.auth.AuthResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.auth.AuthResp} AuthResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            AuthResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies an AuthResp message.
             * @function verify
             * @memberof im.auth.AuthResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            AuthResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                if (message.expireAt != null && Object.hasOwnProperty.call(message, "expireAt"))
                    if (!$util.isInteger(message.expireAt) && !(message.expireAt && $util.isInteger(message.expireAt.low) && $util.isInteger(message.expireAt.high)))
                        return "expireAt: integer|Long expected";
                return null;
            };

            /**
             * Creates an AuthResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.auth.AuthResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.auth.AuthResp} AuthResp
             */
            AuthResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.auth.AuthResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.auth.AuthResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.auth.AuthResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                if (object.userId != null)
                    message.userId = String(object.userId);
                if (object.expireAt != null)
                    if ($util.Long)
                        message.expireAt = $util.Long.fromValue(object.expireAt, false);
                    else if (typeof object.expireAt === "string")
                        message.expireAt = parseInt(object.expireAt, 10);
                    else if (typeof object.expireAt === "number")
                        message.expireAt = object.expireAt;
                    else if (typeof object.expireAt === "object")
                        message.expireAt = new $util.LongBits(object.expireAt.low >>> 0, object.expireAt.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from an AuthResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.auth.AuthResp
             * @static
             * @param {im.auth.AuthResp} message AuthResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            AuthResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                    object.userId = "";
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.expireAt = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.expireAt = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                if (message.expireAt != null && Object.hasOwnProperty.call(message, "expireAt"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.expireAt = typeof message.expireAt === "number" ? BigInt(message.expireAt) : $util.Long.fromBits(message.expireAt.low >>> 0, message.expireAt.high >>> 0, false).toBigInt();
                    else if (typeof message.expireAt === "number")
                        object.expireAt = options.longs === String ? String(message.expireAt) : message.expireAt;
                    else
                        object.expireAt = options.longs === String ? $util.Long.prototype.toString.call(message.expireAt) : options.longs === Number ? new $util.LongBits(message.expireAt.low >>> 0, message.expireAt.high >>> 0).toNumber() : message.expireAt;
                return object;
            };

            /**
             * Converts this AuthResp to JSON.
             * @function toJSON
             * @memberof im.auth.AuthResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            AuthResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for AuthResp
             * @function getTypeUrl
             * @memberof im.auth.AuthResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            AuthResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.auth.AuthResp";
            };

            return AuthResp;
        })();

        auth.LogoutReq = (function() {

            /**
             * Properties of a LogoutReq.
             * @memberof im.auth
             * @interface ILogoutReq
             * @property {string|null} [reason] LogoutReq reason
             */

            /**
             * Constructs a new LogoutReq.
             * @memberof im.auth
             * @classdesc Represents a LogoutReq.
             * @implements ILogoutReq
             * @constructor
             * @param {im.auth.ILogoutReq=} [properties] Properties to set
             */
            function LogoutReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * LogoutReq reason.
             * @member {string} reason
             * @memberof im.auth.LogoutReq
             * @instance
             */
            LogoutReq.prototype.reason = "";

            /**
             * Creates a new LogoutReq instance using the specified properties.
             * @function create
             * @memberof im.auth.LogoutReq
             * @static
             * @param {im.auth.ILogoutReq=} [properties] Properties to set
             * @returns {im.auth.LogoutReq} LogoutReq instance
             */
            LogoutReq.create = function create(properties) {
                return new LogoutReq(properties);
            };

            /**
             * Encodes the specified LogoutReq message. Does not implicitly {@link im.auth.LogoutReq.verify|verify} messages.
             * @function encode
             * @memberof im.auth.LogoutReq
             * @static
             * @param {im.auth.ILogoutReq} message LogoutReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            LogoutReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.reason != null && Object.hasOwnProperty.call(message, "reason"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.reason);
                return writer;
            };

            /**
             * Encodes the specified LogoutReq message, length delimited. Does not implicitly {@link im.auth.LogoutReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.auth.LogoutReq
             * @static
             * @param {im.auth.ILogoutReq} message LogoutReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            LogoutReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a LogoutReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.auth.LogoutReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.auth.LogoutReq} LogoutReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            LogoutReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.auth.LogoutReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.reason = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a LogoutReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.auth.LogoutReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.auth.LogoutReq} LogoutReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            LogoutReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a LogoutReq message.
             * @function verify
             * @memberof im.auth.LogoutReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            LogoutReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.reason != null && Object.hasOwnProperty.call(message, "reason"))
                    if (!$util.isString(message.reason))
                        return "reason: string expected";
                return null;
            };

            /**
             * Creates a LogoutReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.auth.LogoutReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.auth.LogoutReq} LogoutReq
             */
            LogoutReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.auth.LogoutReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.auth.LogoutReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.auth.LogoutReq();
                if (object.reason != null)
                    message.reason = String(object.reason);
                return message;
            };

            /**
             * Creates a plain object from a LogoutReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.auth.LogoutReq
             * @static
             * @param {im.auth.LogoutReq} message LogoutReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            LogoutReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults)
                    object.reason = "";
                if (message.reason != null && Object.hasOwnProperty.call(message, "reason"))
                    object.reason = message.reason;
                return object;
            };

            /**
             * Converts this LogoutReq to JSON.
             * @function toJSON
             * @memberof im.auth.LogoutReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            LogoutReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for LogoutReq
             * @function getTypeUrl
             * @memberof im.auth.LogoutReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            LogoutReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.auth.LogoutReq";
            };

            return LogoutReq;
        })();

        auth.LogoutResp = (function() {

            /**
             * Properties of a LogoutResp.
             * @memberof im.auth
             * @interface ILogoutResp
             * @property {number|null} [code] LogoutResp code
             * @property {string|null} [message] LogoutResp message
             */

            /**
             * Constructs a new LogoutResp.
             * @memberof im.auth
             * @classdesc Represents a LogoutResp.
             * @implements ILogoutResp
             * @constructor
             * @param {im.auth.ILogoutResp=} [properties] Properties to set
             */
            function LogoutResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * LogoutResp code.
             * @member {number} code
             * @memberof im.auth.LogoutResp
             * @instance
             */
            LogoutResp.prototype.code = 0;

            /**
             * LogoutResp message.
             * @member {string} message
             * @memberof im.auth.LogoutResp
             * @instance
             */
            LogoutResp.prototype.message = "";

            /**
             * Creates a new LogoutResp instance using the specified properties.
             * @function create
             * @memberof im.auth.LogoutResp
             * @static
             * @param {im.auth.ILogoutResp=} [properties] Properties to set
             * @returns {im.auth.LogoutResp} LogoutResp instance
             */
            LogoutResp.create = function create(properties) {
                return new LogoutResp(properties);
            };

            /**
             * Encodes the specified LogoutResp message. Does not implicitly {@link im.auth.LogoutResp.verify|verify} messages.
             * @function encode
             * @memberof im.auth.LogoutResp
             * @static
             * @param {im.auth.ILogoutResp} message LogoutResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            LogoutResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                return writer;
            };

            /**
             * Encodes the specified LogoutResp message, length delimited. Does not implicitly {@link im.auth.LogoutResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.auth.LogoutResp
             * @static
             * @param {im.auth.ILogoutResp} message LogoutResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            LogoutResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a LogoutResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.auth.LogoutResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.auth.LogoutResp} LogoutResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            LogoutResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.auth.LogoutResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a LogoutResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.auth.LogoutResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.auth.LogoutResp} LogoutResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            LogoutResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a LogoutResp message.
             * @function verify
             * @memberof im.auth.LogoutResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            LogoutResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                return null;
            };

            /**
             * Creates a LogoutResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.auth.LogoutResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.auth.LogoutResp} LogoutResp
             */
            LogoutResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.auth.LogoutResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.auth.LogoutResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.auth.LogoutResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                return message;
            };

            /**
             * Creates a plain object from a LogoutResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.auth.LogoutResp
             * @static
             * @param {im.auth.LogoutResp} message LogoutResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            LogoutResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                return object;
            };

            /**
             * Converts this LogoutResp to JSON.
             * @function toJSON
             * @memberof im.auth.LogoutResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            LogoutResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for LogoutResp
             * @function getTypeUrl
             * @memberof im.auth.LogoutResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            LogoutResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.auth.LogoutResp";
            };

            return LogoutResp;
        })();

        return auth;
    })();

    im.chat = (function() {

        /**
         * Namespace chat.
         * @memberof im
         * @namespace
         */
        const chat = {};

        chat.C2CReq = (function() {

            /**
             * Properties of a C2CReq.
             * @memberof im.chat
             * @interface IC2CReq
             * @property {string|null} [senderId] C2CReq senderId
             * @property {string|null} [recipientId] C2CReq recipientId
             * @property {number|Long|null} [messageId] C2CReq messageId
             * @property {im.common.IMessageContent|null} [message] C2CReq message
             */

            /**
             * Constructs a new C2CReq.
             * @memberof im.chat
             * @classdesc Represents a C2CReq.
             * @implements IC2CReq
             * @constructor
             * @param {im.chat.IC2CReq=} [properties] Properties to set
             */
            function C2CReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * C2CReq senderId.
             * @member {string} senderId
             * @memberof im.chat.C2CReq
             * @instance
             */
            C2CReq.prototype.senderId = "";

            /**
             * C2CReq recipientId.
             * @member {string} recipientId
             * @memberof im.chat.C2CReq
             * @instance
             */
            C2CReq.prototype.recipientId = "";

            /**
             * C2CReq messageId.
             * @member {number|Long} messageId
             * @memberof im.chat.C2CReq
             * @instance
             */
            C2CReq.prototype.messageId = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * C2CReq message.
             * @member {im.common.IMessageContent|null|undefined} message
             * @memberof im.chat.C2CReq
             * @instance
             */
            C2CReq.prototype.message = null;

            /**
             * Creates a new C2CReq instance using the specified properties.
             * @function create
             * @memberof im.chat.C2CReq
             * @static
             * @param {im.chat.IC2CReq=} [properties] Properties to set
             * @returns {im.chat.C2CReq} C2CReq instance
             */
            C2CReq.create = function create(properties) {
                return new C2CReq(properties);
            };

            /**
             * Encodes the specified C2CReq message. Does not implicitly {@link im.chat.C2CReq.verify|verify} messages.
             * @function encode
             * @memberof im.chat.C2CReq
             * @static
             * @param {im.chat.IC2CReq} message C2CReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2CReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.senderId);
                if (message.recipientId != null && Object.hasOwnProperty.call(message, "recipientId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.recipientId);
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    writer.uint32(/* id 3, wireType 0 =*/24).int64(message.messageId);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    $root.im.common.MessageContent.encode(message.message, writer.uint32(/* id 4, wireType 2 =*/34).fork(), q + 1).ldelim();
                return writer;
            };

            /**
             * Encodes the specified C2CReq message, length delimited. Does not implicitly {@link im.chat.C2CReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.chat.C2CReq
             * @static
             * @param {im.chat.IC2CReq} message C2CReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2CReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a C2CReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.chat.C2CReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.chat.C2CReq} C2CReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2CReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.chat.C2CReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.senderId = reader.string();
                            break;
                        }
                    case 2: {
                            message.recipientId = reader.string();
                            break;
                        }
                    case 3: {
                            message.messageId = reader.int64();
                            break;
                        }
                    case 4: {
                            message.message = $root.im.common.MessageContent.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a C2CReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.chat.C2CReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.chat.C2CReq} C2CReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2CReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a C2CReq message.
             * @function verify
             * @memberof im.chat.C2CReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            C2CReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    if (!$util.isString(message.senderId))
                        return "senderId: string expected";
                if (message.recipientId != null && Object.hasOwnProperty.call(message, "recipientId"))
                    if (!$util.isString(message.recipientId))
                        return "recipientId: string expected";
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (!$util.isInteger(message.messageId) && !(message.messageId && $util.isInteger(message.messageId.low) && $util.isInteger(message.messageId.high)))
                        return "messageId: integer|Long expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message")) {
                    let error = $root.im.common.MessageContent.verify(message.message, long + 1);
                    if (error)
                        return "message." + error;
                }
                return null;
            };

            /**
             * Creates a C2CReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.chat.C2CReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.chat.C2CReq} C2CReq
             */
            C2CReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.chat.C2CReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.chat.C2CReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.chat.C2CReq();
                if (object.senderId != null)
                    message.senderId = String(object.senderId);
                if (object.recipientId != null)
                    message.recipientId = String(object.recipientId);
                if (object.messageId != null)
                    if ($util.Long)
                        message.messageId = $util.Long.fromValue(object.messageId, false);
                    else if (typeof object.messageId === "string")
                        message.messageId = parseInt(object.messageId, 10);
                    else if (typeof object.messageId === "number")
                        message.messageId = object.messageId;
                    else if (typeof object.messageId === "object")
                        message.messageId = new $util.LongBits(object.messageId.low >>> 0, object.messageId.high >>> 0).toNumber();
                if (object.message != null) {
                    if (!$util.isObject(object.message))
                        throw TypeError(".im.chat.C2CReq.message: object expected");
                    message.message = $root.im.common.MessageContent.fromObject(object.message, long + 1);
                }
                return message;
            };

            /**
             * Creates a plain object from a C2CReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.chat.C2CReq
             * @static
             * @param {im.chat.C2CReq} message C2CReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            C2CReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.senderId = "";
                    object.recipientId = "";
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.messageId = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.messageId = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                    object.message = null;
                }
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    object.senderId = message.senderId;
                if (message.recipientId != null && Object.hasOwnProperty.call(message, "recipientId"))
                    object.recipientId = message.recipientId;
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.messageId = typeof message.messageId === "number" ? BigInt(message.messageId) : $util.Long.fromBits(message.messageId.low >>> 0, message.messageId.high >>> 0, false).toBigInt();
                    else if (typeof message.messageId === "number")
                        object.messageId = options.longs === String ? String(message.messageId) : message.messageId;
                    else
                        object.messageId = options.longs === String ? $util.Long.prototype.toString.call(message.messageId) : options.longs === Number ? new $util.LongBits(message.messageId.low >>> 0, message.messageId.high >>> 0).toNumber() : message.messageId;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = $root.im.common.MessageContent.toObject(message.message, options, q + 1);
                return object;
            };

            /**
             * Converts this C2CReq to JSON.
             * @function toJSON
             * @memberof im.chat.C2CReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            C2CReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for C2CReq
             * @function getTypeUrl
             * @memberof im.chat.C2CReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            C2CReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.chat.C2CReq";
            };

            return C2CReq;
        })();

        chat.C2CResp = (function() {

            /**
             * Properties of a C2CResp.
             * @memberof im.chat
             * @interface IC2CResp
             * @property {number|null} [code] C2CResp code
             * @property {string|null} [message] C2CResp message
             * @property {number|Long|null} [messageId] C2CResp messageId
             * @property {number|Long|null} [serverTime] C2CResp serverTime
             * @property {number|Long|null} [seq] C2CResp seq
             */

            /**
             * Constructs a new C2CResp.
             * @memberof im.chat
             * @classdesc Represents a C2CResp.
             * @implements IC2CResp
             * @constructor
             * @param {im.chat.IC2CResp=} [properties] Properties to set
             */
            function C2CResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * C2CResp code.
             * @member {number} code
             * @memberof im.chat.C2CResp
             * @instance
             */
            C2CResp.prototype.code = 0;

            /**
             * C2CResp message.
             * @member {string} message
             * @memberof im.chat.C2CResp
             * @instance
             */
            C2CResp.prototype.message = "";

            /**
             * C2CResp messageId.
             * @member {number|Long} messageId
             * @memberof im.chat.C2CResp
             * @instance
             */
            C2CResp.prototype.messageId = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * C2CResp serverTime.
             * @member {number|Long} serverTime
             * @memberof im.chat.C2CResp
             * @instance
             */
            C2CResp.prototype.serverTime = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * C2CResp seq.
             * @member {number|Long|null|undefined} seq
             * @memberof im.chat.C2CResp
             * @instance
             */
            C2CResp.prototype.seq = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            // Virtual OneOf for proto3 optional field
            Object.defineProperty(C2CResp.prototype, "_seq", {
                get: $util.oneOfGetter($oneOfFields = ["seq"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new C2CResp instance using the specified properties.
             * @function create
             * @memberof im.chat.C2CResp
             * @static
             * @param {im.chat.IC2CResp=} [properties] Properties to set
             * @returns {im.chat.C2CResp} C2CResp instance
             */
            C2CResp.create = function create(properties) {
                return new C2CResp(properties);
            };

            /**
             * Encodes the specified C2CResp message. Does not implicitly {@link im.chat.C2CResp.verify|verify} messages.
             * @function encode
             * @memberof im.chat.C2CResp
             * @static
             * @param {im.chat.IC2CResp} message C2CResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2CResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    writer.uint32(/* id 3, wireType 0 =*/24).int64(message.messageId);
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    writer.uint32(/* id 4, wireType 0 =*/32).int64(message.serverTime);
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq"))
                    writer.uint32(/* id 5, wireType 0 =*/40).int64(message.seq);
                return writer;
            };

            /**
             * Encodes the specified C2CResp message, length delimited. Does not implicitly {@link im.chat.C2CResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.chat.C2CResp
             * @static
             * @param {im.chat.IC2CResp} message C2CResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2CResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a C2CResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.chat.C2CResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.chat.C2CResp} C2CResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2CResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.chat.C2CResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    case 3: {
                            message.messageId = reader.int64();
                            break;
                        }
                    case 4: {
                            message.serverTime = reader.int64();
                            break;
                        }
                    case 5: {
                            message.seq = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a C2CResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.chat.C2CResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.chat.C2CResp} C2CResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2CResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a C2CResp message.
             * @function verify
             * @memberof im.chat.C2CResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            C2CResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (!$util.isInteger(message.messageId) && !(message.messageId && $util.isInteger(message.messageId.low) && $util.isInteger(message.messageId.high)))
                        return "messageId: integer|Long expected";
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    if (!$util.isInteger(message.serverTime) && !(message.serverTime && $util.isInteger(message.serverTime.low) && $util.isInteger(message.serverTime.high)))
                        return "serverTime: integer|Long expected";
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    properties._seq = 1;
                    if (!$util.isInteger(message.seq) && !(message.seq && $util.isInteger(message.seq.low) && $util.isInteger(message.seq.high)))
                        return "seq: integer|Long expected";
                }
                return null;
            };

            /**
             * Creates a C2CResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.chat.C2CResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.chat.C2CResp} C2CResp
             */
            C2CResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.chat.C2CResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.chat.C2CResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.chat.C2CResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                if (object.messageId != null)
                    if ($util.Long)
                        message.messageId = $util.Long.fromValue(object.messageId, false);
                    else if (typeof object.messageId === "string")
                        message.messageId = parseInt(object.messageId, 10);
                    else if (typeof object.messageId === "number")
                        message.messageId = object.messageId;
                    else if (typeof object.messageId === "object")
                        message.messageId = new $util.LongBits(object.messageId.low >>> 0, object.messageId.high >>> 0).toNumber();
                if (object.serverTime != null)
                    if ($util.Long)
                        message.serverTime = $util.Long.fromValue(object.serverTime, false);
                    else if (typeof object.serverTime === "string")
                        message.serverTime = parseInt(object.serverTime, 10);
                    else if (typeof object.serverTime === "number")
                        message.serverTime = object.serverTime;
                    else if (typeof object.serverTime === "object")
                        message.serverTime = new $util.LongBits(object.serverTime.low >>> 0, object.serverTime.high >>> 0).toNumber();
                if (object.seq != null)
                    if ($util.Long)
                        message.seq = $util.Long.fromValue(object.seq, false);
                    else if (typeof object.seq === "string")
                        message.seq = parseInt(object.seq, 10);
                    else if (typeof object.seq === "number")
                        message.seq = object.seq;
                    else if (typeof object.seq === "object")
                        message.seq = new $util.LongBits(object.seq.low >>> 0, object.seq.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a C2CResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.chat.C2CResp
             * @static
             * @param {im.chat.C2CResp} message C2CResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            C2CResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.messageId = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.messageId = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.serverTime = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.serverTime = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.messageId = typeof message.messageId === "number" ? BigInt(message.messageId) : $util.Long.fromBits(message.messageId.low >>> 0, message.messageId.high >>> 0, false).toBigInt();
                    else if (typeof message.messageId === "number")
                        object.messageId = options.longs === String ? String(message.messageId) : message.messageId;
                    else
                        object.messageId = options.longs === String ? $util.Long.prototype.toString.call(message.messageId) : options.longs === Number ? new $util.LongBits(message.messageId.low >>> 0, message.messageId.high >>> 0).toNumber() : message.messageId;
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.serverTime = typeof message.serverTime === "number" ? BigInt(message.serverTime) : $util.Long.fromBits(message.serverTime.low >>> 0, message.serverTime.high >>> 0, false).toBigInt();
                    else if (typeof message.serverTime === "number")
                        object.serverTime = options.longs === String ? String(message.serverTime) : message.serverTime;
                    else
                        object.serverTime = options.longs === String ? $util.Long.prototype.toString.call(message.serverTime) : options.longs === Number ? new $util.LongBits(message.serverTime.low >>> 0, message.serverTime.high >>> 0).toNumber() : message.serverTime;
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.seq = typeof message.seq === "number" ? BigInt(message.seq) : $util.Long.fromBits(message.seq.low >>> 0, message.seq.high >>> 0, false).toBigInt();
                    else if (typeof message.seq === "number")
                        object.seq = options.longs === String ? String(message.seq) : message.seq;
                    else
                        object.seq = options.longs === String ? $util.Long.prototype.toString.call(message.seq) : options.longs === Number ? new $util.LongBits(message.seq.low >>> 0, message.seq.high >>> 0).toNumber() : message.seq;
                    if (options.oneofs)
                        object._seq = "seq";
                }
                return object;
            };

            /**
             * Converts this C2CResp to JSON.
             * @function toJSON
             * @memberof im.chat.C2CResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            C2CResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for C2CResp
             * @function getTypeUrl
             * @memberof im.chat.C2CResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            C2CResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.chat.C2CResp";
            };

            return C2CResp;
        })();

        chat.C2CNotify = (function() {

            /**
             * Properties of a C2CNotify.
             * @memberof im.chat
             * @interface IC2CNotify
             * @property {string|null} [senderId] C2CNotify senderId
             * @property {string|null} [recipientId] C2CNotify recipientId
             * @property {im.common.IMessageContent|null} [message] C2CNotify message
             * @property {number|Long|null} [seq] C2CNotify seq
             */

            /**
             * Constructs a new C2CNotify.
             * @memberof im.chat
             * @classdesc Represents a C2CNotify.
             * @implements IC2CNotify
             * @constructor
             * @param {im.chat.IC2CNotify=} [properties] Properties to set
             */
            function C2CNotify(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * C2CNotify senderId.
             * @member {string} senderId
             * @memberof im.chat.C2CNotify
             * @instance
             */
            C2CNotify.prototype.senderId = "";

            /**
             * C2CNotify recipientId.
             * @member {string} recipientId
             * @memberof im.chat.C2CNotify
             * @instance
             */
            C2CNotify.prototype.recipientId = "";

            /**
             * C2CNotify message.
             * @member {im.common.IMessageContent|null|undefined} message
             * @memberof im.chat.C2CNotify
             * @instance
             */
            C2CNotify.prototype.message = null;

            /**
             * C2CNotify seq.
             * @member {number|Long|null|undefined} seq
             * @memberof im.chat.C2CNotify
             * @instance
             */
            C2CNotify.prototype.seq = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            // Virtual OneOf for proto3 optional field
            Object.defineProperty(C2CNotify.prototype, "_seq", {
                get: $util.oneOfGetter($oneOfFields = ["seq"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new C2CNotify instance using the specified properties.
             * @function create
             * @memberof im.chat.C2CNotify
             * @static
             * @param {im.chat.IC2CNotify=} [properties] Properties to set
             * @returns {im.chat.C2CNotify} C2CNotify instance
             */
            C2CNotify.create = function create(properties) {
                return new C2CNotify(properties);
            };

            /**
             * Encodes the specified C2CNotify message. Does not implicitly {@link im.chat.C2CNotify.verify|verify} messages.
             * @function encode
             * @memberof im.chat.C2CNotify
             * @static
             * @param {im.chat.IC2CNotify} message C2CNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2CNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.senderId);
                if (message.recipientId != null && Object.hasOwnProperty.call(message, "recipientId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.recipientId);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    $root.im.common.MessageContent.encode(message.message, writer.uint32(/* id 3, wireType 2 =*/26).fork(), q + 1).ldelim();
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq"))
                    writer.uint32(/* id 4, wireType 0 =*/32).int64(message.seq);
                return writer;
            };

            /**
             * Encodes the specified C2CNotify message, length delimited. Does not implicitly {@link im.chat.C2CNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.chat.C2CNotify
             * @static
             * @param {im.chat.IC2CNotify} message C2CNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2CNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a C2CNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.chat.C2CNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.chat.C2CNotify} C2CNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2CNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.chat.C2CNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.senderId = reader.string();
                            break;
                        }
                    case 2: {
                            message.recipientId = reader.string();
                            break;
                        }
                    case 3: {
                            message.message = $root.im.common.MessageContent.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 4: {
                            message.seq = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a C2CNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.chat.C2CNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.chat.C2CNotify} C2CNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2CNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a C2CNotify message.
             * @function verify
             * @memberof im.chat.C2CNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            C2CNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    if (!$util.isString(message.senderId))
                        return "senderId: string expected";
                if (message.recipientId != null && Object.hasOwnProperty.call(message, "recipientId"))
                    if (!$util.isString(message.recipientId))
                        return "recipientId: string expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message")) {
                    let error = $root.im.common.MessageContent.verify(message.message, long + 1);
                    if (error)
                        return "message." + error;
                }
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    properties._seq = 1;
                    if (!$util.isInteger(message.seq) && !(message.seq && $util.isInteger(message.seq.low) && $util.isInteger(message.seq.high)))
                        return "seq: integer|Long expected";
                }
                return null;
            };

            /**
             * Creates a C2CNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.chat.C2CNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.chat.C2CNotify} C2CNotify
             */
            C2CNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.chat.C2CNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.chat.C2CNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.chat.C2CNotify();
                if (object.senderId != null)
                    message.senderId = String(object.senderId);
                if (object.recipientId != null)
                    message.recipientId = String(object.recipientId);
                if (object.message != null) {
                    if (!$util.isObject(object.message))
                        throw TypeError(".im.chat.C2CNotify.message: object expected");
                    message.message = $root.im.common.MessageContent.fromObject(object.message, long + 1);
                }
                if (object.seq != null)
                    if ($util.Long)
                        message.seq = $util.Long.fromValue(object.seq, false);
                    else if (typeof object.seq === "string")
                        message.seq = parseInt(object.seq, 10);
                    else if (typeof object.seq === "number")
                        message.seq = object.seq;
                    else if (typeof object.seq === "object")
                        message.seq = new $util.LongBits(object.seq.low >>> 0, object.seq.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a C2CNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.chat.C2CNotify
             * @static
             * @param {im.chat.C2CNotify} message C2CNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            C2CNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.senderId = "";
                    object.recipientId = "";
                    object.message = null;
                }
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    object.senderId = message.senderId;
                if (message.recipientId != null && Object.hasOwnProperty.call(message, "recipientId"))
                    object.recipientId = message.recipientId;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = $root.im.common.MessageContent.toObject(message.message, options, q + 1);
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.seq = typeof message.seq === "number" ? BigInt(message.seq) : $util.Long.fromBits(message.seq.low >>> 0, message.seq.high >>> 0, false).toBigInt();
                    else if (typeof message.seq === "number")
                        object.seq = options.longs === String ? String(message.seq) : message.seq;
                    else
                        object.seq = options.longs === String ? $util.Long.prototype.toString.call(message.seq) : options.longs === Number ? new $util.LongBits(message.seq.low >>> 0, message.seq.high >>> 0).toNumber() : message.seq;
                    if (options.oneofs)
                        object._seq = "seq";
                }
                return object;
            };

            /**
             * Converts this C2CNotify to JSON.
             * @function toJSON
             * @memberof im.chat.C2CNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            C2CNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for C2CNotify
             * @function getTypeUrl
             * @memberof im.chat.C2CNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            C2CNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.chat.C2CNotify";
            };

            return C2CNotify;
        })();

        return chat;
    })();

    im.ctrl = (function() {

        /**
         * Namespace ctrl.
         * @memberof im
         * @namespace
         */
        const ctrl = {};

        /**
         * CtrlType enum.
         * @name im.ctrl.CtrlType
         * @enum {number}
         * @property {number} CTRL_TYPE_UNKNOWN=0 CTRL_TYPE_UNKNOWN value
         * @property {number} CTRL_TYPE_KICK_OFFLINE=1 CTRL_TYPE_KICK_OFFLINE value
         * @property {number} CTRL_TYPE_FORCE_LOGOUT=2 CTRL_TYPE_FORCE_LOGOUT value
         * @property {number} CTRL_TYPE_NOTIFY=3 CTRL_TYPE_NOTIFY value
         * @property {number} CTRL_TYPE_SYNC=4 CTRL_TYPE_SYNC value
         */
        ctrl.CtrlType = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "CTRL_TYPE_UNKNOWN"] = 0;
            values[valuesById[1] = "CTRL_TYPE_KICK_OFFLINE"] = 1;
            values[valuesById[2] = "CTRL_TYPE_FORCE_LOGOUT"] = 2;
            values[valuesById[3] = "CTRL_TYPE_NOTIFY"] = 3;
            values[valuesById[4] = "CTRL_TYPE_SYNC"] = 4;
            return values;
        })();

        ctrl.CtrlReq = (function() {

            /**
             * Properties of a CtrlReq.
             * @memberof im.ctrl
             * @interface ICtrlReq
             * @property {im.ctrl.CtrlType|null} [ctrlType] CtrlReq ctrlType
             * @property {string|null} [targetUser] CtrlReq targetUser
             * @property {Uint8Array|null} [payload] CtrlReq payload
             */

            /**
             * Constructs a new CtrlReq.
             * @memberof im.ctrl
             * @classdesc Represents a CtrlReq.
             * @implements ICtrlReq
             * @constructor
             * @param {im.ctrl.ICtrlReq=} [properties] Properties to set
             */
            function CtrlReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * CtrlReq ctrlType.
             * @member {im.ctrl.CtrlType} ctrlType
             * @memberof im.ctrl.CtrlReq
             * @instance
             */
            CtrlReq.prototype.ctrlType = 0;

            /**
             * CtrlReq targetUser.
             * @member {string} targetUser
             * @memberof im.ctrl.CtrlReq
             * @instance
             */
            CtrlReq.prototype.targetUser = "";

            /**
             * CtrlReq payload.
             * @member {Uint8Array|null|undefined} payload
             * @memberof im.ctrl.CtrlReq
             * @instance
             */
            CtrlReq.prototype.payload = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            // Virtual OneOf for proto3 optional field
            Object.defineProperty(CtrlReq.prototype, "_payload", {
                get: $util.oneOfGetter($oneOfFields = ["payload"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new CtrlReq instance using the specified properties.
             * @function create
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {im.ctrl.ICtrlReq=} [properties] Properties to set
             * @returns {im.ctrl.CtrlReq} CtrlReq instance
             */
            CtrlReq.create = function create(properties) {
                return new CtrlReq(properties);
            };

            /**
             * Encodes the specified CtrlReq message. Does not implicitly {@link im.ctrl.CtrlReq.verify|verify} messages.
             * @function encode
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {im.ctrl.ICtrlReq} message CtrlReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            CtrlReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.ctrlType != null && Object.hasOwnProperty.call(message, "ctrlType"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.ctrlType);
                if (message.targetUser != null && Object.hasOwnProperty.call(message, "targetUser"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.targetUser);
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    writer.uint32(/* id 3, wireType 2 =*/26).bytes(message.payload);
                return writer;
            };

            /**
             * Encodes the specified CtrlReq message, length delimited. Does not implicitly {@link im.ctrl.CtrlReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {im.ctrl.ICtrlReq} message CtrlReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            CtrlReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a CtrlReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.ctrl.CtrlReq} CtrlReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            CtrlReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.ctrl.CtrlReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.ctrlType = reader.int32();
                            break;
                        }
                    case 2: {
                            message.targetUser = reader.string();
                            break;
                        }
                    case 3: {
                            message.payload = reader.bytes();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a CtrlReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.ctrl.CtrlReq} CtrlReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            CtrlReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a CtrlReq message.
             * @function verify
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            CtrlReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.ctrlType != null && Object.hasOwnProperty.call(message, "ctrlType"))
                    switch (message.ctrlType) {
                    default:
                        return "ctrlType: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                        break;
                    }
                if (message.targetUser != null && Object.hasOwnProperty.call(message, "targetUser"))
                    if (!$util.isString(message.targetUser))
                        return "targetUser: string expected";
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload")) {
                    properties._payload = 1;
                    if (!(message.payload && typeof message.payload.length === "number" || $util.isString(message.payload)))
                        return "payload: buffer expected";
                }
                return null;
            };

            /**
             * Creates a CtrlReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.ctrl.CtrlReq} CtrlReq
             */
            CtrlReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.ctrl.CtrlReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.ctrl.CtrlReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.ctrl.CtrlReq();
                switch (object.ctrlType) {
                default:
                    if (typeof object.ctrlType === "number") {
                        message.ctrlType = object.ctrlType;
                        break;
                    }
                    break;
                case "CTRL_TYPE_UNKNOWN":
                case 0:
                    message.ctrlType = 0;
                    break;
                case "CTRL_TYPE_KICK_OFFLINE":
                case 1:
                    message.ctrlType = 1;
                    break;
                case "CTRL_TYPE_FORCE_LOGOUT":
                case 2:
                    message.ctrlType = 2;
                    break;
                case "CTRL_TYPE_NOTIFY":
                case 3:
                    message.ctrlType = 3;
                    break;
                case "CTRL_TYPE_SYNC":
                case 4:
                    message.ctrlType = 4;
                    break;
                }
                if (object.targetUser != null)
                    message.targetUser = String(object.targetUser);
                if (object.payload != null)
                    if (typeof object.payload === "string")
                        $util.base64.decode(object.payload, message.payload = $util.newBuffer($util.base64.length(object.payload)), 0);
                    else if (object.payload.length >= 0)
                        message.payload = object.payload;
                return message;
            };

            /**
             * Creates a plain object from a CtrlReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {im.ctrl.CtrlReq} message CtrlReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            CtrlReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.ctrlType = options.enums === String ? "CTRL_TYPE_UNKNOWN" : 0;
                    object.targetUser = "";
                }
                if (message.ctrlType != null && Object.hasOwnProperty.call(message, "ctrlType"))
                    object.ctrlType = options.enums === String ? $root.im.ctrl.CtrlType[message.ctrlType] === undefined ? message.ctrlType : $root.im.ctrl.CtrlType[message.ctrlType] : message.ctrlType;
                if (message.targetUser != null && Object.hasOwnProperty.call(message, "targetUser"))
                    object.targetUser = message.targetUser;
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload")) {
                    object.payload = options.bytes === String ? $util.base64.encode(message.payload, 0, message.payload.length) : options.bytes === Array ? Array.prototype.slice.call(message.payload) : message.payload;
                    if (options.oneofs)
                        object._payload = "payload";
                }
                return object;
            };

            /**
             * Converts this CtrlReq to JSON.
             * @function toJSON
             * @memberof im.ctrl.CtrlReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            CtrlReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for CtrlReq
             * @function getTypeUrl
             * @memberof im.ctrl.CtrlReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            CtrlReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.ctrl.CtrlReq";
            };

            return CtrlReq;
        })();

        ctrl.CtrlResp = (function() {

            /**
             * Properties of a CtrlResp.
             * @memberof im.ctrl
             * @interface ICtrlResp
             * @property {number|null} [code] CtrlResp code
             * @property {string|null} [message] CtrlResp message
             * @property {Uint8Array|null} [payload] CtrlResp payload
             */

            /**
             * Constructs a new CtrlResp.
             * @memberof im.ctrl
             * @classdesc Represents a CtrlResp.
             * @implements ICtrlResp
             * @constructor
             * @param {im.ctrl.ICtrlResp=} [properties] Properties to set
             */
            function CtrlResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * CtrlResp code.
             * @member {number} code
             * @memberof im.ctrl.CtrlResp
             * @instance
             */
            CtrlResp.prototype.code = 0;

            /**
             * CtrlResp message.
             * @member {string} message
             * @memberof im.ctrl.CtrlResp
             * @instance
             */
            CtrlResp.prototype.message = "";

            /**
             * CtrlResp payload.
             * @member {Uint8Array} payload
             * @memberof im.ctrl.CtrlResp
             * @instance
             */
            CtrlResp.prototype.payload = $util.newBuffer([]);

            /**
             * Creates a new CtrlResp instance using the specified properties.
             * @function create
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {im.ctrl.ICtrlResp=} [properties] Properties to set
             * @returns {im.ctrl.CtrlResp} CtrlResp instance
             */
            CtrlResp.create = function create(properties) {
                return new CtrlResp(properties);
            };

            /**
             * Encodes the specified CtrlResp message. Does not implicitly {@link im.ctrl.CtrlResp.verify|verify} messages.
             * @function encode
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {im.ctrl.ICtrlResp} message CtrlResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            CtrlResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    writer.uint32(/* id 3, wireType 2 =*/26).bytes(message.payload);
                return writer;
            };

            /**
             * Encodes the specified CtrlResp message, length delimited. Does not implicitly {@link im.ctrl.CtrlResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {im.ctrl.ICtrlResp} message CtrlResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            CtrlResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a CtrlResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.ctrl.CtrlResp} CtrlResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            CtrlResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.ctrl.CtrlResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    case 3: {
                            message.payload = reader.bytes();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a CtrlResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.ctrl.CtrlResp} CtrlResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            CtrlResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a CtrlResp message.
             * @function verify
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            CtrlResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    if (!(message.payload && typeof message.payload.length === "number" || $util.isString(message.payload)))
                        return "payload: buffer expected";
                return null;
            };

            /**
             * Creates a CtrlResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.ctrl.CtrlResp} CtrlResp
             */
            CtrlResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.ctrl.CtrlResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.ctrl.CtrlResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.ctrl.CtrlResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                if (object.payload != null)
                    if (typeof object.payload === "string")
                        $util.base64.decode(object.payload, message.payload = $util.newBuffer($util.base64.length(object.payload)), 0);
                    else if (object.payload.length >= 0)
                        message.payload = object.payload;
                return message;
            };

            /**
             * Creates a plain object from a CtrlResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {im.ctrl.CtrlResp} message CtrlResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            CtrlResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                    if (options.bytes === String)
                        object.payload = "";
                    else {
                        object.payload = [];
                        if (options.bytes !== Array)
                            object.payload = $util.newBuffer(object.payload);
                    }
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    object.payload = options.bytes === String ? $util.base64.encode(message.payload, 0, message.payload.length) : options.bytes === Array ? Array.prototype.slice.call(message.payload) : message.payload;
                return object;
            };

            /**
             * Converts this CtrlResp to JSON.
             * @function toJSON
             * @memberof im.ctrl.CtrlResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            CtrlResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for CtrlResp
             * @function getTypeUrl
             * @memberof im.ctrl.CtrlResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            CtrlResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.ctrl.CtrlResp";
            };

            return CtrlResp;
        })();

        ctrl.CtrlNotify = (function() {

            /**
             * Properties of a CtrlNotify.
             * @memberof im.ctrl
             * @interface ICtrlNotify
             * @property {im.ctrl.CtrlType|null} [ctrlType] CtrlNotify ctrlType
             * @property {string|null} [reason] CtrlNotify reason
             * @property {Uint8Array|null} [payload] CtrlNotify payload
             */

            /**
             * Constructs a new CtrlNotify.
             * @memberof im.ctrl
             * @classdesc Represents a CtrlNotify.
             * @implements ICtrlNotify
             * @constructor
             * @param {im.ctrl.ICtrlNotify=} [properties] Properties to set
             */
            function CtrlNotify(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * CtrlNotify ctrlType.
             * @member {im.ctrl.CtrlType} ctrlType
             * @memberof im.ctrl.CtrlNotify
             * @instance
             */
            CtrlNotify.prototype.ctrlType = 0;

            /**
             * CtrlNotify reason.
             * @member {string} reason
             * @memberof im.ctrl.CtrlNotify
             * @instance
             */
            CtrlNotify.prototype.reason = "";

            /**
             * CtrlNotify payload.
             * @member {Uint8Array} payload
             * @memberof im.ctrl.CtrlNotify
             * @instance
             */
            CtrlNotify.prototype.payload = $util.newBuffer([]);

            /**
             * Creates a new CtrlNotify instance using the specified properties.
             * @function create
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {im.ctrl.ICtrlNotify=} [properties] Properties to set
             * @returns {im.ctrl.CtrlNotify} CtrlNotify instance
             */
            CtrlNotify.create = function create(properties) {
                return new CtrlNotify(properties);
            };

            /**
             * Encodes the specified CtrlNotify message. Does not implicitly {@link im.ctrl.CtrlNotify.verify|verify} messages.
             * @function encode
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {im.ctrl.ICtrlNotify} message CtrlNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            CtrlNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.ctrlType != null && Object.hasOwnProperty.call(message, "ctrlType"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.ctrlType);
                if (message.reason != null && Object.hasOwnProperty.call(message, "reason"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.reason);
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    writer.uint32(/* id 3, wireType 2 =*/26).bytes(message.payload);
                return writer;
            };

            /**
             * Encodes the specified CtrlNotify message, length delimited. Does not implicitly {@link im.ctrl.CtrlNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {im.ctrl.ICtrlNotify} message CtrlNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            CtrlNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a CtrlNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.ctrl.CtrlNotify} CtrlNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            CtrlNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.ctrl.CtrlNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.ctrlType = reader.int32();
                            break;
                        }
                    case 2: {
                            message.reason = reader.string();
                            break;
                        }
                    case 3: {
                            message.payload = reader.bytes();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a CtrlNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.ctrl.CtrlNotify} CtrlNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            CtrlNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a CtrlNotify message.
             * @function verify
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            CtrlNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.ctrlType != null && Object.hasOwnProperty.call(message, "ctrlType"))
                    switch (message.ctrlType) {
                    default:
                        return "ctrlType: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                        break;
                    }
                if (message.reason != null && Object.hasOwnProperty.call(message, "reason"))
                    if (!$util.isString(message.reason))
                        return "reason: string expected";
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    if (!(message.payload && typeof message.payload.length === "number" || $util.isString(message.payload)))
                        return "payload: buffer expected";
                return null;
            };

            /**
             * Creates a CtrlNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.ctrl.CtrlNotify} CtrlNotify
             */
            CtrlNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.ctrl.CtrlNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.ctrl.CtrlNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.ctrl.CtrlNotify();
                switch (object.ctrlType) {
                default:
                    if (typeof object.ctrlType === "number") {
                        message.ctrlType = object.ctrlType;
                        break;
                    }
                    break;
                case "CTRL_TYPE_UNKNOWN":
                case 0:
                    message.ctrlType = 0;
                    break;
                case "CTRL_TYPE_KICK_OFFLINE":
                case 1:
                    message.ctrlType = 1;
                    break;
                case "CTRL_TYPE_FORCE_LOGOUT":
                case 2:
                    message.ctrlType = 2;
                    break;
                case "CTRL_TYPE_NOTIFY":
                case 3:
                    message.ctrlType = 3;
                    break;
                case "CTRL_TYPE_SYNC":
                case 4:
                    message.ctrlType = 4;
                    break;
                }
                if (object.reason != null)
                    message.reason = String(object.reason);
                if (object.payload != null)
                    if (typeof object.payload === "string")
                        $util.base64.decode(object.payload, message.payload = $util.newBuffer($util.base64.length(object.payload)), 0);
                    else if (object.payload.length >= 0)
                        message.payload = object.payload;
                return message;
            };

            /**
             * Creates a plain object from a CtrlNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {im.ctrl.CtrlNotify} message CtrlNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            CtrlNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.ctrlType = options.enums === String ? "CTRL_TYPE_UNKNOWN" : 0;
                    object.reason = "";
                    if (options.bytes === String)
                        object.payload = "";
                    else {
                        object.payload = [];
                        if (options.bytes !== Array)
                            object.payload = $util.newBuffer(object.payload);
                    }
                }
                if (message.ctrlType != null && Object.hasOwnProperty.call(message, "ctrlType"))
                    object.ctrlType = options.enums === String ? $root.im.ctrl.CtrlType[message.ctrlType] === undefined ? message.ctrlType : $root.im.ctrl.CtrlType[message.ctrlType] : message.ctrlType;
                if (message.reason != null && Object.hasOwnProperty.call(message, "reason"))
                    object.reason = message.reason;
                if (message.payload != null && Object.hasOwnProperty.call(message, "payload"))
                    object.payload = options.bytes === String ? $util.base64.encode(message.payload, 0, message.payload.length) : options.bytes === Array ? Array.prototype.slice.call(message.payload) : message.payload;
                return object;
            };

            /**
             * Converts this CtrlNotify to JSON.
             * @function toJSON
             * @memberof im.ctrl.CtrlNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            CtrlNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for CtrlNotify
             * @function getTypeUrl
             * @memberof im.ctrl.CtrlNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            CtrlNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.ctrl.CtrlNotify";
            };

            return CtrlNotify;
        })();

        return ctrl;
    })();

    im.group = (function() {

        /**
         * Namespace group.
         * @memberof im
         * @namespace
         */
        const group = {};

        group.C2GReq = (function() {

            /**
             * Properties of a C2GReq.
             * @memberof im.group
             * @interface IC2GReq
             * @property {string|null} [senderId] C2GReq senderId
             * @property {string|null} [groupId] C2GReq groupId
             * @property {number|Long|null} [messageId] C2GReq messageId
             * @property {im.common.IMessageContent|null} [message] C2GReq message
             */

            /**
             * Constructs a new C2GReq.
             * @memberof im.group
             * @classdesc Represents a C2GReq.
             * @implements IC2GReq
             * @constructor
             * @param {im.group.IC2GReq=} [properties] Properties to set
             */
            function C2GReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * C2GReq senderId.
             * @member {string} senderId
             * @memberof im.group.C2GReq
             * @instance
             */
            C2GReq.prototype.senderId = "";

            /**
             * C2GReq groupId.
             * @member {string} groupId
             * @memberof im.group.C2GReq
             * @instance
             */
            C2GReq.prototype.groupId = "";

            /**
             * C2GReq messageId.
             * @member {number|Long} messageId
             * @memberof im.group.C2GReq
             * @instance
             */
            C2GReq.prototype.messageId = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * C2GReq message.
             * @member {im.common.IMessageContent|null|undefined} message
             * @memberof im.group.C2GReq
             * @instance
             */
            C2GReq.prototype.message = null;

            /**
             * Creates a new C2GReq instance using the specified properties.
             * @function create
             * @memberof im.group.C2GReq
             * @static
             * @param {im.group.IC2GReq=} [properties] Properties to set
             * @returns {im.group.C2GReq} C2GReq instance
             */
            C2GReq.create = function create(properties) {
                return new C2GReq(properties);
            };

            /**
             * Encodes the specified C2GReq message. Does not implicitly {@link im.group.C2GReq.verify|verify} messages.
             * @function encode
             * @memberof im.group.C2GReq
             * @static
             * @param {im.group.IC2GReq} message C2GReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2GReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.senderId);
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.groupId);
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    writer.uint32(/* id 3, wireType 0 =*/24).int64(message.messageId);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    $root.im.common.MessageContent.encode(message.message, writer.uint32(/* id 4, wireType 2 =*/34).fork(), q + 1).ldelim();
                return writer;
            };

            /**
             * Encodes the specified C2GReq message, length delimited. Does not implicitly {@link im.group.C2GReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.group.C2GReq
             * @static
             * @param {im.group.IC2GReq} message C2GReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2GReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a C2GReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.group.C2GReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.group.C2GReq} C2GReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2GReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.group.C2GReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.senderId = reader.string();
                            break;
                        }
                    case 2: {
                            message.groupId = reader.string();
                            break;
                        }
                    case 3: {
                            message.messageId = reader.int64();
                            break;
                        }
                    case 4: {
                            message.message = $root.im.common.MessageContent.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a C2GReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.group.C2GReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.group.C2GReq} C2GReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2GReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a C2GReq message.
             * @function verify
             * @memberof im.group.C2GReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            C2GReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    if (!$util.isString(message.senderId))
                        return "senderId: string expected";
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    if (!$util.isString(message.groupId))
                        return "groupId: string expected";
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (!$util.isInteger(message.messageId) && !(message.messageId && $util.isInteger(message.messageId.low) && $util.isInteger(message.messageId.high)))
                        return "messageId: integer|Long expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message")) {
                    let error = $root.im.common.MessageContent.verify(message.message, long + 1);
                    if (error)
                        return "message." + error;
                }
                return null;
            };

            /**
             * Creates a C2GReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.group.C2GReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.group.C2GReq} C2GReq
             */
            C2GReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.group.C2GReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.group.C2GReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.group.C2GReq();
                if (object.senderId != null)
                    message.senderId = String(object.senderId);
                if (object.groupId != null)
                    message.groupId = String(object.groupId);
                if (object.messageId != null)
                    if ($util.Long)
                        message.messageId = $util.Long.fromValue(object.messageId, false);
                    else if (typeof object.messageId === "string")
                        message.messageId = parseInt(object.messageId, 10);
                    else if (typeof object.messageId === "number")
                        message.messageId = object.messageId;
                    else if (typeof object.messageId === "object")
                        message.messageId = new $util.LongBits(object.messageId.low >>> 0, object.messageId.high >>> 0).toNumber();
                if (object.message != null) {
                    if (!$util.isObject(object.message))
                        throw TypeError(".im.group.C2GReq.message: object expected");
                    message.message = $root.im.common.MessageContent.fromObject(object.message, long + 1);
                }
                return message;
            };

            /**
             * Creates a plain object from a C2GReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.group.C2GReq
             * @static
             * @param {im.group.C2GReq} message C2GReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            C2GReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.senderId = "";
                    object.groupId = "";
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.messageId = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.messageId = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                    object.message = null;
                }
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    object.senderId = message.senderId;
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    object.groupId = message.groupId;
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.messageId = typeof message.messageId === "number" ? BigInt(message.messageId) : $util.Long.fromBits(message.messageId.low >>> 0, message.messageId.high >>> 0, false).toBigInt();
                    else if (typeof message.messageId === "number")
                        object.messageId = options.longs === String ? String(message.messageId) : message.messageId;
                    else
                        object.messageId = options.longs === String ? $util.Long.prototype.toString.call(message.messageId) : options.longs === Number ? new $util.LongBits(message.messageId.low >>> 0, message.messageId.high >>> 0).toNumber() : message.messageId;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = $root.im.common.MessageContent.toObject(message.message, options, q + 1);
                return object;
            };

            /**
             * Converts this C2GReq to JSON.
             * @function toJSON
             * @memberof im.group.C2GReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            C2GReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for C2GReq
             * @function getTypeUrl
             * @memberof im.group.C2GReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            C2GReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.group.C2GReq";
            };

            return C2GReq;
        })();

        group.C2GResp = (function() {

            /**
             * Properties of a C2GResp.
             * @memberof im.group
             * @interface IC2GResp
             * @property {number|null} [code] C2GResp code
             * @property {string|null} [message] C2GResp message
             * @property {number|Long|null} [messageId] C2GResp messageId
             * @property {string|null} [groupId] C2GResp groupId
             * @property {number|Long|null} [serverTime] C2GResp serverTime
             * @property {number|Long|null} [seq] C2GResp seq
             */

            /**
             * Constructs a new C2GResp.
             * @memberof im.group
             * @classdesc Represents a C2GResp.
             * @implements IC2GResp
             * @constructor
             * @param {im.group.IC2GResp=} [properties] Properties to set
             */
            function C2GResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * C2GResp code.
             * @member {number} code
             * @memberof im.group.C2GResp
             * @instance
             */
            C2GResp.prototype.code = 0;

            /**
             * C2GResp message.
             * @member {string} message
             * @memberof im.group.C2GResp
             * @instance
             */
            C2GResp.prototype.message = "";

            /**
             * C2GResp messageId.
             * @member {number|Long} messageId
             * @memberof im.group.C2GResp
             * @instance
             */
            C2GResp.prototype.messageId = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * C2GResp groupId.
             * @member {string} groupId
             * @memberof im.group.C2GResp
             * @instance
             */
            C2GResp.prototype.groupId = "";

            /**
             * C2GResp serverTime.
             * @member {number|Long} serverTime
             * @memberof im.group.C2GResp
             * @instance
             */
            C2GResp.prototype.serverTime = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * C2GResp seq.
             * @member {number|Long|null|undefined} seq
             * @memberof im.group.C2GResp
             * @instance
             */
            C2GResp.prototype.seq = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            // Virtual OneOf for proto3 optional field
            Object.defineProperty(C2GResp.prototype, "_seq", {
                get: $util.oneOfGetter($oneOfFields = ["seq"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new C2GResp instance using the specified properties.
             * @function create
             * @memberof im.group.C2GResp
             * @static
             * @param {im.group.IC2GResp=} [properties] Properties to set
             * @returns {im.group.C2GResp} C2GResp instance
             */
            C2GResp.create = function create(properties) {
                return new C2GResp(properties);
            };

            /**
             * Encodes the specified C2GResp message. Does not implicitly {@link im.group.C2GResp.verify|verify} messages.
             * @function encode
             * @memberof im.group.C2GResp
             * @static
             * @param {im.group.IC2GResp} message C2GResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2GResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    writer.uint32(/* id 3, wireType 0 =*/24).int64(message.messageId);
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    writer.uint32(/* id 4, wireType 2 =*/34).string(message.groupId);
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    writer.uint32(/* id 5, wireType 0 =*/40).int64(message.serverTime);
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq"))
                    writer.uint32(/* id 6, wireType 0 =*/48).int64(message.seq);
                return writer;
            };

            /**
             * Encodes the specified C2GResp message, length delimited. Does not implicitly {@link im.group.C2GResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.group.C2GResp
             * @static
             * @param {im.group.IC2GResp} message C2GResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2GResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a C2GResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.group.C2GResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.group.C2GResp} C2GResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2GResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.group.C2GResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    case 3: {
                            message.messageId = reader.int64();
                            break;
                        }
                    case 4: {
                            message.groupId = reader.string();
                            break;
                        }
                    case 5: {
                            message.serverTime = reader.int64();
                            break;
                        }
                    case 6: {
                            message.seq = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a C2GResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.group.C2GResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.group.C2GResp} C2GResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2GResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a C2GResp message.
             * @function verify
             * @memberof im.group.C2GResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            C2GResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (!$util.isInteger(message.messageId) && !(message.messageId && $util.isInteger(message.messageId.low) && $util.isInteger(message.messageId.high)))
                        return "messageId: integer|Long expected";
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    if (!$util.isString(message.groupId))
                        return "groupId: string expected";
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    if (!$util.isInteger(message.serverTime) && !(message.serverTime && $util.isInteger(message.serverTime.low) && $util.isInteger(message.serverTime.high)))
                        return "serverTime: integer|Long expected";
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    properties._seq = 1;
                    if (!$util.isInteger(message.seq) && !(message.seq && $util.isInteger(message.seq.low) && $util.isInteger(message.seq.high)))
                        return "seq: integer|Long expected";
                }
                return null;
            };

            /**
             * Creates a C2GResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.group.C2GResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.group.C2GResp} C2GResp
             */
            C2GResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.group.C2GResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.group.C2GResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.group.C2GResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                if (object.messageId != null)
                    if ($util.Long)
                        message.messageId = $util.Long.fromValue(object.messageId, false);
                    else if (typeof object.messageId === "string")
                        message.messageId = parseInt(object.messageId, 10);
                    else if (typeof object.messageId === "number")
                        message.messageId = object.messageId;
                    else if (typeof object.messageId === "object")
                        message.messageId = new $util.LongBits(object.messageId.low >>> 0, object.messageId.high >>> 0).toNumber();
                if (object.groupId != null)
                    message.groupId = String(object.groupId);
                if (object.serverTime != null)
                    if ($util.Long)
                        message.serverTime = $util.Long.fromValue(object.serverTime, false);
                    else if (typeof object.serverTime === "string")
                        message.serverTime = parseInt(object.serverTime, 10);
                    else if (typeof object.serverTime === "number")
                        message.serverTime = object.serverTime;
                    else if (typeof object.serverTime === "object")
                        message.serverTime = new $util.LongBits(object.serverTime.low >>> 0, object.serverTime.high >>> 0).toNumber();
                if (object.seq != null)
                    if ($util.Long)
                        message.seq = $util.Long.fromValue(object.seq, false);
                    else if (typeof object.seq === "string")
                        message.seq = parseInt(object.seq, 10);
                    else if (typeof object.seq === "number")
                        message.seq = object.seq;
                    else if (typeof object.seq === "object")
                        message.seq = new $util.LongBits(object.seq.low >>> 0, object.seq.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a C2GResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.group.C2GResp
             * @static
             * @param {im.group.C2GResp} message C2GResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            C2GResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.messageId = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.messageId = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                    object.groupId = "";
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.serverTime = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.serverTime = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                if (message.messageId != null && Object.hasOwnProperty.call(message, "messageId"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.messageId = typeof message.messageId === "number" ? BigInt(message.messageId) : $util.Long.fromBits(message.messageId.low >>> 0, message.messageId.high >>> 0, false).toBigInt();
                    else if (typeof message.messageId === "number")
                        object.messageId = options.longs === String ? String(message.messageId) : message.messageId;
                    else
                        object.messageId = options.longs === String ? $util.Long.prototype.toString.call(message.messageId) : options.longs === Number ? new $util.LongBits(message.messageId.low >>> 0, message.messageId.high >>> 0).toNumber() : message.messageId;
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    object.groupId = message.groupId;
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.serverTime = typeof message.serverTime === "number" ? BigInt(message.serverTime) : $util.Long.fromBits(message.serverTime.low >>> 0, message.serverTime.high >>> 0, false).toBigInt();
                    else if (typeof message.serverTime === "number")
                        object.serverTime = options.longs === String ? String(message.serverTime) : message.serverTime;
                    else
                        object.serverTime = options.longs === String ? $util.Long.prototype.toString.call(message.serverTime) : options.longs === Number ? new $util.LongBits(message.serverTime.low >>> 0, message.serverTime.high >>> 0).toNumber() : message.serverTime;
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.seq = typeof message.seq === "number" ? BigInt(message.seq) : $util.Long.fromBits(message.seq.low >>> 0, message.seq.high >>> 0, false).toBigInt();
                    else if (typeof message.seq === "number")
                        object.seq = options.longs === String ? String(message.seq) : message.seq;
                    else
                        object.seq = options.longs === String ? $util.Long.prototype.toString.call(message.seq) : options.longs === Number ? new $util.LongBits(message.seq.low >>> 0, message.seq.high >>> 0).toNumber() : message.seq;
                    if (options.oneofs)
                        object._seq = "seq";
                }
                return object;
            };

            /**
             * Converts this C2GResp to JSON.
             * @function toJSON
             * @memberof im.group.C2GResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            C2GResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for C2GResp
             * @function getTypeUrl
             * @memberof im.group.C2GResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            C2GResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.group.C2GResp";
            };

            return C2GResp;
        })();

        group.C2GNotify = (function() {

            /**
             * Properties of a C2GNotify.
             * @memberof im.group
             * @interface IC2GNotify
             * @property {string|null} [senderId] C2GNotify senderId
             * @property {string|null} [groupId] C2GNotify groupId
             * @property {im.common.IMessageContent|null} [message] C2GNotify message
             * @property {number|Long|null} [seq] C2GNotify seq
             */

            /**
             * Constructs a new C2GNotify.
             * @memberof im.group
             * @classdesc Represents a C2GNotify.
             * @implements IC2GNotify
             * @constructor
             * @param {im.group.IC2GNotify=} [properties] Properties to set
             */
            function C2GNotify(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * C2GNotify senderId.
             * @member {string} senderId
             * @memberof im.group.C2GNotify
             * @instance
             */
            C2GNotify.prototype.senderId = "";

            /**
             * C2GNotify groupId.
             * @member {string} groupId
             * @memberof im.group.C2GNotify
             * @instance
             */
            C2GNotify.prototype.groupId = "";

            /**
             * C2GNotify message.
             * @member {im.common.IMessageContent|null|undefined} message
             * @memberof im.group.C2GNotify
             * @instance
             */
            C2GNotify.prototype.message = null;

            /**
             * C2GNotify seq.
             * @member {number|Long|null|undefined} seq
             * @memberof im.group.C2GNotify
             * @instance
             */
            C2GNotify.prototype.seq = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            // Virtual OneOf for proto3 optional field
            Object.defineProperty(C2GNotify.prototype, "_seq", {
                get: $util.oneOfGetter($oneOfFields = ["seq"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new C2GNotify instance using the specified properties.
             * @function create
             * @memberof im.group.C2GNotify
             * @static
             * @param {im.group.IC2GNotify=} [properties] Properties to set
             * @returns {im.group.C2GNotify} C2GNotify instance
             */
            C2GNotify.create = function create(properties) {
                return new C2GNotify(properties);
            };

            /**
             * Encodes the specified C2GNotify message. Does not implicitly {@link im.group.C2GNotify.verify|verify} messages.
             * @function encode
             * @memberof im.group.C2GNotify
             * @static
             * @param {im.group.IC2GNotify} message C2GNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2GNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.senderId);
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.groupId);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    $root.im.common.MessageContent.encode(message.message, writer.uint32(/* id 3, wireType 2 =*/26).fork(), q + 1).ldelim();
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq"))
                    writer.uint32(/* id 5, wireType 0 =*/40).int64(message.seq);
                return writer;
            };

            /**
             * Encodes the specified C2GNotify message, length delimited. Does not implicitly {@link im.group.C2GNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.group.C2GNotify
             * @static
             * @param {im.group.IC2GNotify} message C2GNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            C2GNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a C2GNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.group.C2GNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.group.C2GNotify} C2GNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2GNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.group.C2GNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.senderId = reader.string();
                            break;
                        }
                    case 2: {
                            message.groupId = reader.string();
                            break;
                        }
                    case 3: {
                            message.message = $root.im.common.MessageContent.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 5: {
                            message.seq = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a C2GNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.group.C2GNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.group.C2GNotify} C2GNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            C2GNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a C2GNotify message.
             * @function verify
             * @memberof im.group.C2GNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            C2GNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    if (!$util.isString(message.senderId))
                        return "senderId: string expected";
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    if (!$util.isString(message.groupId))
                        return "groupId: string expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message")) {
                    let error = $root.im.common.MessageContent.verify(message.message, long + 1);
                    if (error)
                        return "message." + error;
                }
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    properties._seq = 1;
                    if (!$util.isInteger(message.seq) && !(message.seq && $util.isInteger(message.seq.low) && $util.isInteger(message.seq.high)))
                        return "seq: integer|Long expected";
                }
                return null;
            };

            /**
             * Creates a C2GNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.group.C2GNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.group.C2GNotify} C2GNotify
             */
            C2GNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.group.C2GNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.group.C2GNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.group.C2GNotify();
                if (object.senderId != null)
                    message.senderId = String(object.senderId);
                if (object.groupId != null)
                    message.groupId = String(object.groupId);
                if (object.message != null) {
                    if (!$util.isObject(object.message))
                        throw TypeError(".im.group.C2GNotify.message: object expected");
                    message.message = $root.im.common.MessageContent.fromObject(object.message, long + 1);
                }
                if (object.seq != null)
                    if ($util.Long)
                        message.seq = $util.Long.fromValue(object.seq, false);
                    else if (typeof object.seq === "string")
                        message.seq = parseInt(object.seq, 10);
                    else if (typeof object.seq === "number")
                        message.seq = object.seq;
                    else if (typeof object.seq === "object")
                        message.seq = new $util.LongBits(object.seq.low >>> 0, object.seq.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a C2GNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.group.C2GNotify
             * @static
             * @param {im.group.C2GNotify} message C2GNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            C2GNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.senderId = "";
                    object.groupId = "";
                    object.message = null;
                }
                if (message.senderId != null && Object.hasOwnProperty.call(message, "senderId"))
                    object.senderId = message.senderId;
                if (message.groupId != null && Object.hasOwnProperty.call(message, "groupId"))
                    object.groupId = message.groupId;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = $root.im.common.MessageContent.toObject(message.message, options, q + 1);
                if (message.seq != null && Object.hasOwnProperty.call(message, "seq")) {
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.seq = typeof message.seq === "number" ? BigInt(message.seq) : $util.Long.fromBits(message.seq.low >>> 0, message.seq.high >>> 0, false).toBigInt();
                    else if (typeof message.seq === "number")
                        object.seq = options.longs === String ? String(message.seq) : message.seq;
                    else
                        object.seq = options.longs === String ? $util.Long.prototype.toString.call(message.seq) : options.longs === Number ? new $util.LongBits(message.seq.low >>> 0, message.seq.high >>> 0).toNumber() : message.seq;
                    if (options.oneofs)
                        object._seq = "seq";
                }
                return object;
            };

            /**
             * Converts this C2GNotify to JSON.
             * @function toJSON
             * @memberof im.group.C2GNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            C2GNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for C2GNotify
             * @function getTypeUrl
             * @memberof im.group.C2GNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            C2GNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.group.C2GNotify";
            };

            return C2GNotify;
        })();

        return group;
    })();

    im.heartbeat = (function() {

        /**
         * Namespace heartbeat.
         * @memberof im
         * @namespace
         */
        const heartbeat = {};

        heartbeat.Ping = (function() {

            /**
             * Properties of a Ping.
             * @memberof im.heartbeat
             * @interface IPing
             * @property {number|Long|null} [clientTime] Ping clientTime
             */

            /**
             * Constructs a new Ping.
             * @memberof im.heartbeat
             * @classdesc Represents a Ping.
             * @implements IPing
             * @constructor
             * @param {im.heartbeat.IPing=} [properties] Properties to set
             */
            function Ping(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * Ping clientTime.
             * @member {number|Long} clientTime
             * @memberof im.heartbeat.Ping
             * @instance
             */
            Ping.prototype.clientTime = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * Creates a new Ping instance using the specified properties.
             * @function create
             * @memberof im.heartbeat.Ping
             * @static
             * @param {im.heartbeat.IPing=} [properties] Properties to set
             * @returns {im.heartbeat.Ping} Ping instance
             */
            Ping.create = function create(properties) {
                return new Ping(properties);
            };

            /**
             * Encodes the specified Ping message. Does not implicitly {@link im.heartbeat.Ping.verify|verify} messages.
             * @function encode
             * @memberof im.heartbeat.Ping
             * @static
             * @param {im.heartbeat.IPing} message Ping message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Ping.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.clientTime != null && Object.hasOwnProperty.call(message, "clientTime"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int64(message.clientTime);
                return writer;
            };

            /**
             * Encodes the specified Ping message, length delimited. Does not implicitly {@link im.heartbeat.Ping.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.heartbeat.Ping
             * @static
             * @param {im.heartbeat.IPing} message Ping message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Ping.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a Ping message from the specified reader or buffer.
             * @function decode
             * @memberof im.heartbeat.Ping
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.heartbeat.Ping} Ping
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Ping.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.heartbeat.Ping();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.clientTime = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a Ping message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.heartbeat.Ping
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.heartbeat.Ping} Ping
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Ping.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a Ping message.
             * @function verify
             * @memberof im.heartbeat.Ping
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            Ping.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.clientTime != null && Object.hasOwnProperty.call(message, "clientTime"))
                    if (!$util.isInteger(message.clientTime) && !(message.clientTime && $util.isInteger(message.clientTime.low) && $util.isInteger(message.clientTime.high)))
                        return "clientTime: integer|Long expected";
                return null;
            };

            /**
             * Creates a Ping message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.heartbeat.Ping
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.heartbeat.Ping} Ping
             */
            Ping.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.heartbeat.Ping)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.heartbeat.Ping: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.heartbeat.Ping();
                if (object.clientTime != null)
                    if ($util.Long)
                        message.clientTime = $util.Long.fromValue(object.clientTime, false);
                    else if (typeof object.clientTime === "string")
                        message.clientTime = parseInt(object.clientTime, 10);
                    else if (typeof object.clientTime === "number")
                        message.clientTime = object.clientTime;
                    else if (typeof object.clientTime === "object")
                        message.clientTime = new $util.LongBits(object.clientTime.low >>> 0, object.clientTime.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a Ping message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.heartbeat.Ping
             * @static
             * @param {im.heartbeat.Ping} message Ping
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            Ping.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults)
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.clientTime = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.clientTime = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                if (message.clientTime != null && Object.hasOwnProperty.call(message, "clientTime"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.clientTime = typeof message.clientTime === "number" ? BigInt(message.clientTime) : $util.Long.fromBits(message.clientTime.low >>> 0, message.clientTime.high >>> 0, false).toBigInt();
                    else if (typeof message.clientTime === "number")
                        object.clientTime = options.longs === String ? String(message.clientTime) : message.clientTime;
                    else
                        object.clientTime = options.longs === String ? $util.Long.prototype.toString.call(message.clientTime) : options.longs === Number ? new $util.LongBits(message.clientTime.low >>> 0, message.clientTime.high >>> 0).toNumber() : message.clientTime;
                return object;
            };

            /**
             * Converts this Ping to JSON.
             * @function toJSON
             * @memberof im.heartbeat.Ping
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            Ping.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for Ping
             * @function getTypeUrl
             * @memberof im.heartbeat.Ping
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            Ping.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.heartbeat.Ping";
            };

            return Ping;
        })();

        heartbeat.Pong = (function() {

            /**
             * Properties of a Pong.
             * @memberof im.heartbeat
             * @interface IPong
             * @property {number|Long|null} [serverTime] Pong serverTime
             * @property {number|Long|null} [clientTime] Pong clientTime
             */

            /**
             * Constructs a new Pong.
             * @memberof im.heartbeat
             * @classdesc Represents a Pong.
             * @implements IPong
             * @constructor
             * @param {im.heartbeat.IPong=} [properties] Properties to set
             */
            function Pong(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * Pong serverTime.
             * @member {number|Long} serverTime
             * @memberof im.heartbeat.Pong
             * @instance
             */
            Pong.prototype.serverTime = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * Pong clientTime.
             * @member {number|Long} clientTime
             * @memberof im.heartbeat.Pong
             * @instance
             */
            Pong.prototype.clientTime = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * Creates a new Pong instance using the specified properties.
             * @function create
             * @memberof im.heartbeat.Pong
             * @static
             * @param {im.heartbeat.IPong=} [properties] Properties to set
             * @returns {im.heartbeat.Pong} Pong instance
             */
            Pong.create = function create(properties) {
                return new Pong(properties);
            };

            /**
             * Encodes the specified Pong message. Does not implicitly {@link im.heartbeat.Pong.verify|verify} messages.
             * @function encode
             * @memberof im.heartbeat.Pong
             * @static
             * @param {im.heartbeat.IPong} message Pong message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Pong.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int64(message.serverTime);
                if (message.clientTime != null && Object.hasOwnProperty.call(message, "clientTime"))
                    writer.uint32(/* id 2, wireType 0 =*/16).int64(message.clientTime);
                return writer;
            };

            /**
             * Encodes the specified Pong message, length delimited. Does not implicitly {@link im.heartbeat.Pong.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.heartbeat.Pong
             * @static
             * @param {im.heartbeat.IPong} message Pong message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Pong.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a Pong message from the specified reader or buffer.
             * @function decode
             * @memberof im.heartbeat.Pong
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.heartbeat.Pong} Pong
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Pong.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.heartbeat.Pong();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.serverTime = reader.int64();
                            break;
                        }
                    case 2: {
                            message.clientTime = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a Pong message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.heartbeat.Pong
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.heartbeat.Pong} Pong
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Pong.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a Pong message.
             * @function verify
             * @memberof im.heartbeat.Pong
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            Pong.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    if (!$util.isInteger(message.serverTime) && !(message.serverTime && $util.isInteger(message.serverTime.low) && $util.isInteger(message.serverTime.high)))
                        return "serverTime: integer|Long expected";
                if (message.clientTime != null && Object.hasOwnProperty.call(message, "clientTime"))
                    if (!$util.isInteger(message.clientTime) && !(message.clientTime && $util.isInteger(message.clientTime.low) && $util.isInteger(message.clientTime.high)))
                        return "clientTime: integer|Long expected";
                return null;
            };

            /**
             * Creates a Pong message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.heartbeat.Pong
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.heartbeat.Pong} Pong
             */
            Pong.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.heartbeat.Pong)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.heartbeat.Pong: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.heartbeat.Pong();
                if (object.serverTime != null)
                    if ($util.Long)
                        message.serverTime = $util.Long.fromValue(object.serverTime, false);
                    else if (typeof object.serverTime === "string")
                        message.serverTime = parseInt(object.serverTime, 10);
                    else if (typeof object.serverTime === "number")
                        message.serverTime = object.serverTime;
                    else if (typeof object.serverTime === "object")
                        message.serverTime = new $util.LongBits(object.serverTime.low >>> 0, object.serverTime.high >>> 0).toNumber();
                if (object.clientTime != null)
                    if ($util.Long)
                        message.clientTime = $util.Long.fromValue(object.clientTime, false);
                    else if (typeof object.clientTime === "string")
                        message.clientTime = parseInt(object.clientTime, 10);
                    else if (typeof object.clientTime === "number")
                        message.clientTime = object.clientTime;
                    else if (typeof object.clientTime === "object")
                        message.clientTime = new $util.LongBits(object.clientTime.low >>> 0, object.clientTime.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a Pong message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.heartbeat.Pong
             * @static
             * @param {im.heartbeat.Pong} message Pong
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            Pong.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.serverTime = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.serverTime = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.clientTime = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.clientTime = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                }
                if (message.serverTime != null && Object.hasOwnProperty.call(message, "serverTime"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.serverTime = typeof message.serverTime === "number" ? BigInt(message.serverTime) : $util.Long.fromBits(message.serverTime.low >>> 0, message.serverTime.high >>> 0, false).toBigInt();
                    else if (typeof message.serverTime === "number")
                        object.serverTime = options.longs === String ? String(message.serverTime) : message.serverTime;
                    else
                        object.serverTime = options.longs === String ? $util.Long.prototype.toString.call(message.serverTime) : options.longs === Number ? new $util.LongBits(message.serverTime.low >>> 0, message.serverTime.high >>> 0).toNumber() : message.serverTime;
                if (message.clientTime != null && Object.hasOwnProperty.call(message, "clientTime"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.clientTime = typeof message.clientTime === "number" ? BigInt(message.clientTime) : $util.Long.fromBits(message.clientTime.low >>> 0, message.clientTime.high >>> 0, false).toBigInt();
                    else if (typeof message.clientTime === "number")
                        object.clientTime = options.longs === String ? String(message.clientTime) : message.clientTime;
                    else
                        object.clientTime = options.longs === String ? $util.Long.prototype.toString.call(message.clientTime) : options.longs === Number ? new $util.LongBits(message.clientTime.low >>> 0, message.clientTime.high >>> 0).toNumber() : message.clientTime;
                return object;
            };

            /**
             * Converts this Pong to JSON.
             * @function toJSON
             * @memberof im.heartbeat.Pong
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            Pong.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for Pong
             * @function getTypeUrl
             * @memberof im.heartbeat.Pong
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            Pong.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.heartbeat.Pong";
            };

            return Pong;
        })();

        return heartbeat;
    })();

    im.message = (function() {

        /**
         * Namespace message.
         * @memberof im
         * @namespace
         */
        const message = {};

        message.MsgBody = (function() {

            /**
             * Properties of a MsgBody.
             * @memberof im.message
             * @interface IMsgBody
             * @property {im.common.Cmd|null} [cmd] MsgBody cmd
             * @property {im.auth.IAuthReq|null} [authReq] MsgBody authReq
             * @property {im.auth.IAuthResp|null} [authResp] MsgBody authResp
             * @property {im.auth.ILogoutReq|null} [logoutReq] MsgBody logoutReq
             * @property {im.auth.ILogoutResp|null} [logoutResp] MsgBody logoutResp
             * @property {im.chat.IC2CReq|null} [c2cReq] MsgBody c2cReq
             * @property {im.chat.IC2CResp|null} [c2cResp] MsgBody c2cResp
             * @property {im.chat.IC2CNotify|null} [c2cNotify] MsgBody c2cNotify
             * @property {im.group.IC2GReq|null} [c2gReq] MsgBody c2gReq
             * @property {im.group.IC2GResp|null} [c2gResp] MsgBody c2gResp
             * @property {im.group.IC2GNotify|null} [c2gNotify] MsgBody c2gNotify
             * @property {im.pull.IPullReq|null} [pullReq] MsgBody pullReq
             * @property {im.pull.IPullResp|null} [pullResp] MsgBody pullResp
             * @property {im.ctrl.ICtrlReq|null} [ctrlReq] MsgBody ctrlReq
             * @property {im.ctrl.ICtrlResp|null} [ctrlResp] MsgBody ctrlResp
             * @property {im.ctrl.ICtrlNotify|null} [ctrlPush] MsgBody ctrlPush
             * @property {im.heartbeat.IPing|null} [ping] MsgBody ping
             * @property {im.heartbeat.IPong|null} [pong] MsgBody pong
             * @property {im.ack.IAckReq|null} [ackReq] MsgBody ackReq
             * @property {im.ack.IAckResp|null} [ackResp] MsgBody ackResp
             * @property {im.ack.IAckNotify|null} [ackNotify] MsgBody ackNotify
             */

            /**
             * Constructs a new MsgBody.
             * @memberof im.message
             * @classdesc Represents a MsgBody.
             * @implements IMsgBody
             * @constructor
             * @param {im.message.IMsgBody=} [properties] Properties to set
             */
            function MsgBody(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * MsgBody cmd.
             * @member {im.common.Cmd} cmd
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.cmd = 0;

            /**
             * MsgBody authReq.
             * @member {im.auth.IAuthReq|null|undefined} authReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.authReq = null;

            /**
             * MsgBody authResp.
             * @member {im.auth.IAuthResp|null|undefined} authResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.authResp = null;

            /**
             * MsgBody logoutReq.
             * @member {im.auth.ILogoutReq|null|undefined} logoutReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.logoutReq = null;

            /**
             * MsgBody logoutResp.
             * @member {im.auth.ILogoutResp|null|undefined} logoutResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.logoutResp = null;

            /**
             * MsgBody c2cReq.
             * @member {im.chat.IC2CReq|null|undefined} c2cReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.c2cReq = null;

            /**
             * MsgBody c2cResp.
             * @member {im.chat.IC2CResp|null|undefined} c2cResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.c2cResp = null;

            /**
             * MsgBody c2cNotify.
             * @member {im.chat.IC2CNotify|null|undefined} c2cNotify
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.c2cNotify = null;

            /**
             * MsgBody c2gReq.
             * @member {im.group.IC2GReq|null|undefined} c2gReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.c2gReq = null;

            /**
             * MsgBody c2gResp.
             * @member {im.group.IC2GResp|null|undefined} c2gResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.c2gResp = null;

            /**
             * MsgBody c2gNotify.
             * @member {im.group.IC2GNotify|null|undefined} c2gNotify
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.c2gNotify = null;

            /**
             * MsgBody pullReq.
             * @member {im.pull.IPullReq|null|undefined} pullReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.pullReq = null;

            /**
             * MsgBody pullResp.
             * @member {im.pull.IPullResp|null|undefined} pullResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.pullResp = null;

            /**
             * MsgBody ctrlReq.
             * @member {im.ctrl.ICtrlReq|null|undefined} ctrlReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ctrlReq = null;

            /**
             * MsgBody ctrlResp.
             * @member {im.ctrl.ICtrlResp|null|undefined} ctrlResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ctrlResp = null;

            /**
             * MsgBody ctrlPush.
             * @member {im.ctrl.ICtrlNotify|null|undefined} ctrlPush
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ctrlPush = null;

            /**
             * MsgBody ping.
             * @member {im.heartbeat.IPing|null|undefined} ping
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ping = null;

            /**
             * MsgBody pong.
             * @member {im.heartbeat.IPong|null|undefined} pong
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.pong = null;

            /**
             * MsgBody ackReq.
             * @member {im.ack.IAckReq|null|undefined} ackReq
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ackReq = null;

            /**
             * MsgBody ackResp.
             * @member {im.ack.IAckResp|null|undefined} ackResp
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ackResp = null;

            /**
             * MsgBody ackNotify.
             * @member {im.ack.IAckNotify|null|undefined} ackNotify
             * @memberof im.message.MsgBody
             * @instance
             */
            MsgBody.prototype.ackNotify = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            /**
             * MsgBody body.
             * @member {"authReq"|"authResp"|"logoutReq"|"logoutResp"|"c2cReq"|"c2cResp"|"c2cNotify"|"c2gReq"|"c2gResp"|"c2gNotify"|"pullReq"|"pullResp"|"ctrlReq"|"ctrlResp"|"ctrlPush"|"ping"|"pong"|"ackReq"|"ackResp"|"ackNotify"|undefined} body
             * @memberof im.message.MsgBody
             * @instance
             */
            Object.defineProperty(MsgBody.prototype, "body", {
                get: $util.oneOfGetter($oneOfFields = ["authReq", "authResp", "logoutReq", "logoutResp", "c2cReq", "c2cResp", "c2cNotify", "c2gReq", "c2gResp", "c2gNotify", "pullReq", "pullResp", "ctrlReq", "ctrlResp", "ctrlPush", "ping", "pong", "ackReq", "ackResp", "ackNotify"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new MsgBody instance using the specified properties.
             * @function create
             * @memberof im.message.MsgBody
             * @static
             * @param {im.message.IMsgBody=} [properties] Properties to set
             * @returns {im.message.MsgBody} MsgBody instance
             */
            MsgBody.create = function create(properties) {
                return new MsgBody(properties);
            };

            /**
             * Encodes the specified MsgBody message. Does not implicitly {@link im.message.MsgBody.verify|verify} messages.
             * @function encode
             * @memberof im.message.MsgBody
             * @static
             * @param {im.message.IMsgBody} message MsgBody message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            MsgBody.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.cmd != null && Object.hasOwnProperty.call(message, "cmd"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.cmd);
                if (message.authReq != null && Object.hasOwnProperty.call(message, "authReq"))
                    $root.im.auth.AuthReq.encode(message.authReq, writer.uint32(/* id 10, wireType 2 =*/82).fork(), q + 1).ldelim();
                if (message.authResp != null && Object.hasOwnProperty.call(message, "authResp"))
                    $root.im.auth.AuthResp.encode(message.authResp, writer.uint32(/* id 11, wireType 2 =*/90).fork(), q + 1).ldelim();
                if (message.logoutReq != null && Object.hasOwnProperty.call(message, "logoutReq"))
                    $root.im.auth.LogoutReq.encode(message.logoutReq, writer.uint32(/* id 12, wireType 2 =*/98).fork(), q + 1).ldelim();
                if (message.logoutResp != null && Object.hasOwnProperty.call(message, "logoutResp"))
                    $root.im.auth.LogoutResp.encode(message.logoutResp, writer.uint32(/* id 13, wireType 2 =*/106).fork(), q + 1).ldelim();
                if (message.c2cReq != null && Object.hasOwnProperty.call(message, "c2cReq"))
                    $root.im.chat.C2CReq.encode(message.c2cReq, writer.uint32(/* id 20, wireType 2 =*/162).fork(), q + 1).ldelim();
                if (message.c2cResp != null && Object.hasOwnProperty.call(message, "c2cResp"))
                    $root.im.chat.C2CResp.encode(message.c2cResp, writer.uint32(/* id 21, wireType 2 =*/170).fork(), q + 1).ldelim();
                if (message.c2cNotify != null && Object.hasOwnProperty.call(message, "c2cNotify"))
                    $root.im.chat.C2CNotify.encode(message.c2cNotify, writer.uint32(/* id 22, wireType 2 =*/178).fork(), q + 1).ldelim();
                if (message.c2gReq != null && Object.hasOwnProperty.call(message, "c2gReq"))
                    $root.im.group.C2GReq.encode(message.c2gReq, writer.uint32(/* id 30, wireType 2 =*/242).fork(), q + 1).ldelim();
                if (message.c2gResp != null && Object.hasOwnProperty.call(message, "c2gResp"))
                    $root.im.group.C2GResp.encode(message.c2gResp, writer.uint32(/* id 31, wireType 2 =*/250).fork(), q + 1).ldelim();
                if (message.c2gNotify != null && Object.hasOwnProperty.call(message, "c2gNotify"))
                    $root.im.group.C2GNotify.encode(message.c2gNotify, writer.uint32(/* id 32, wireType 2 =*/258).fork(), q + 1).ldelim();
                if (message.pullReq != null && Object.hasOwnProperty.call(message, "pullReq"))
                    $root.im.pull.PullReq.encode(message.pullReq, writer.uint32(/* id 40, wireType 2 =*/322).fork(), q + 1).ldelim();
                if (message.pullResp != null && Object.hasOwnProperty.call(message, "pullResp"))
                    $root.im.pull.PullResp.encode(message.pullResp, writer.uint32(/* id 41, wireType 2 =*/330).fork(), q + 1).ldelim();
                if (message.ctrlReq != null && Object.hasOwnProperty.call(message, "ctrlReq"))
                    $root.im.ctrl.CtrlReq.encode(message.ctrlReq, writer.uint32(/* id 50, wireType 2 =*/402).fork(), q + 1).ldelim();
                if (message.ctrlResp != null && Object.hasOwnProperty.call(message, "ctrlResp"))
                    $root.im.ctrl.CtrlResp.encode(message.ctrlResp, writer.uint32(/* id 51, wireType 2 =*/410).fork(), q + 1).ldelim();
                if (message.ctrlPush != null && Object.hasOwnProperty.call(message, "ctrlPush"))
                    $root.im.ctrl.CtrlNotify.encode(message.ctrlPush, writer.uint32(/* id 52, wireType 2 =*/418).fork(), q + 1).ldelim();
                if (message.ping != null && Object.hasOwnProperty.call(message, "ping"))
                    $root.im.heartbeat.Ping.encode(message.ping, writer.uint32(/* id 60, wireType 2 =*/482).fork(), q + 1).ldelim();
                if (message.pong != null && Object.hasOwnProperty.call(message, "pong"))
                    $root.im.heartbeat.Pong.encode(message.pong, writer.uint32(/* id 61, wireType 2 =*/490).fork(), q + 1).ldelim();
                if (message.ackReq != null && Object.hasOwnProperty.call(message, "ackReq"))
                    $root.im.ack.AckReq.encode(message.ackReq, writer.uint32(/* id 62, wireType 2 =*/498).fork(), q + 1).ldelim();
                if (message.ackResp != null && Object.hasOwnProperty.call(message, "ackResp"))
                    $root.im.ack.AckResp.encode(message.ackResp, writer.uint32(/* id 63, wireType 2 =*/506).fork(), q + 1).ldelim();
                if (message.ackNotify != null && Object.hasOwnProperty.call(message, "ackNotify"))
                    $root.im.ack.AckNotify.encode(message.ackNotify, writer.uint32(/* id 64, wireType 2 =*/514).fork(), q + 1).ldelim();
                return writer;
            };

            /**
             * Encodes the specified MsgBody message, length delimited. Does not implicitly {@link im.message.MsgBody.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.message.MsgBody
             * @static
             * @param {im.message.IMsgBody} message MsgBody message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            MsgBody.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a MsgBody message from the specified reader or buffer.
             * @function decode
             * @memberof im.message.MsgBody
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.message.MsgBody} MsgBody
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            MsgBody.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.message.MsgBody();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.cmd = reader.int32();
                            break;
                        }
                    case 10: {
                            message.authReq = $root.im.auth.AuthReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 11: {
                            message.authResp = $root.im.auth.AuthResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 12: {
                            message.logoutReq = $root.im.auth.LogoutReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 13: {
                            message.logoutResp = $root.im.auth.LogoutResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 20: {
                            message.c2cReq = $root.im.chat.C2CReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 21: {
                            message.c2cResp = $root.im.chat.C2CResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 22: {
                            message.c2cNotify = $root.im.chat.C2CNotify.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 30: {
                            message.c2gReq = $root.im.group.C2GReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 31: {
                            message.c2gResp = $root.im.group.C2GResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 32: {
                            message.c2gNotify = $root.im.group.C2GNotify.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 40: {
                            message.pullReq = $root.im.pull.PullReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 41: {
                            message.pullResp = $root.im.pull.PullResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 50: {
                            message.ctrlReq = $root.im.ctrl.CtrlReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 51: {
                            message.ctrlResp = $root.im.ctrl.CtrlResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 52: {
                            message.ctrlPush = $root.im.ctrl.CtrlNotify.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 60: {
                            message.ping = $root.im.heartbeat.Ping.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 61: {
                            message.pong = $root.im.heartbeat.Pong.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 62: {
                            message.ackReq = $root.im.ack.AckReq.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 63: {
                            message.ackResp = $root.im.ack.AckResp.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 64: {
                            message.ackNotify = $root.im.ack.AckNotify.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a MsgBody message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.message.MsgBody
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.message.MsgBody} MsgBody
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            MsgBody.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a MsgBody message.
             * @function verify
             * @memberof im.message.MsgBody
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            MsgBody.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.cmd != null && Object.hasOwnProperty.call(message, "cmd"))
                    switch (message.cmd) {
                    default:
                        return "cmd: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 16:
                    case 17:
                    case 18:
                    case 32:
                    case 33:
                    case 34:
                    case 48:
                    case 49:
                    case 64:
                    case 65:
                    case 66:
                    case 80:
                    case 81:
                    case 82:
                    case 83:
                    case 84:
                    case 96:
                    case 97:
                    case 98:
                    case 99:
                    case 100:
                    case 101:
                    case 102:
                    case 103:
                    case 104:
                    case 105:
                    case 106:
                    case 65535:
                        break;
                    }
                if (message.authReq != null && Object.hasOwnProperty.call(message, "authReq")) {
                    properties.body = 1;
                    {
                        let error = $root.im.auth.AuthReq.verify(message.authReq, long + 1);
                        if (error)
                            return "authReq." + error;
                    }
                }
                if (message.authResp != null && Object.hasOwnProperty.call(message, "authResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.auth.AuthResp.verify(message.authResp, long + 1);
                        if (error)
                            return "authResp." + error;
                    }
                }
                if (message.logoutReq != null && Object.hasOwnProperty.call(message, "logoutReq")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.auth.LogoutReq.verify(message.logoutReq, long + 1);
                        if (error)
                            return "logoutReq." + error;
                    }
                }
                if (message.logoutResp != null && Object.hasOwnProperty.call(message, "logoutResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.auth.LogoutResp.verify(message.logoutResp, long + 1);
                        if (error)
                            return "logoutResp." + error;
                    }
                }
                if (message.c2cReq != null && Object.hasOwnProperty.call(message, "c2cReq")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.chat.C2CReq.verify(message.c2cReq, long + 1);
                        if (error)
                            return "c2cReq." + error;
                    }
                }
                if (message.c2cResp != null && Object.hasOwnProperty.call(message, "c2cResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.chat.C2CResp.verify(message.c2cResp, long + 1);
                        if (error)
                            return "c2cResp." + error;
                    }
                }
                if (message.c2cNotify != null && Object.hasOwnProperty.call(message, "c2cNotify")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.chat.C2CNotify.verify(message.c2cNotify, long + 1);
                        if (error)
                            return "c2cNotify." + error;
                    }
                }
                if (message.c2gReq != null && Object.hasOwnProperty.call(message, "c2gReq")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.group.C2GReq.verify(message.c2gReq, long + 1);
                        if (error)
                            return "c2gReq." + error;
                    }
                }
                if (message.c2gResp != null && Object.hasOwnProperty.call(message, "c2gResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.group.C2GResp.verify(message.c2gResp, long + 1);
                        if (error)
                            return "c2gResp." + error;
                    }
                }
                if (message.c2gNotify != null && Object.hasOwnProperty.call(message, "c2gNotify")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.group.C2GNotify.verify(message.c2gNotify, long + 1);
                        if (error)
                            return "c2gNotify." + error;
                    }
                }
                if (message.pullReq != null && Object.hasOwnProperty.call(message, "pullReq")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.pull.PullReq.verify(message.pullReq, long + 1);
                        if (error)
                            return "pullReq." + error;
                    }
                }
                if (message.pullResp != null && Object.hasOwnProperty.call(message, "pullResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.pull.PullResp.verify(message.pullResp, long + 1);
                        if (error)
                            return "pullResp." + error;
                    }
                }
                if (message.ctrlReq != null && Object.hasOwnProperty.call(message, "ctrlReq")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.ctrl.CtrlReq.verify(message.ctrlReq, long + 1);
                        if (error)
                            return "ctrlReq." + error;
                    }
                }
                if (message.ctrlResp != null && Object.hasOwnProperty.call(message, "ctrlResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.ctrl.CtrlResp.verify(message.ctrlResp, long + 1);
                        if (error)
                            return "ctrlResp." + error;
                    }
                }
                if (message.ctrlPush != null && Object.hasOwnProperty.call(message, "ctrlPush")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.ctrl.CtrlNotify.verify(message.ctrlPush, long + 1);
                        if (error)
                            return "ctrlPush." + error;
                    }
                }
                if (message.ping != null && Object.hasOwnProperty.call(message, "ping")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.heartbeat.Ping.verify(message.ping, long + 1);
                        if (error)
                            return "ping." + error;
                    }
                }
                if (message.pong != null && Object.hasOwnProperty.call(message, "pong")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.heartbeat.Pong.verify(message.pong, long + 1);
                        if (error)
                            return "pong." + error;
                    }
                }
                if (message.ackReq != null && Object.hasOwnProperty.call(message, "ackReq")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.ack.AckReq.verify(message.ackReq, long + 1);
                        if (error)
                            return "ackReq." + error;
                    }
                }
                if (message.ackResp != null && Object.hasOwnProperty.call(message, "ackResp")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.ack.AckResp.verify(message.ackResp, long + 1);
                        if (error)
                            return "ackResp." + error;
                    }
                }
                if (message.ackNotify != null && Object.hasOwnProperty.call(message, "ackNotify")) {
                    if (properties.body === 1)
                        return "body: multiple values";
                    properties.body = 1;
                    {
                        let error = $root.im.ack.AckNotify.verify(message.ackNotify, long + 1);
                        if (error)
                            return "ackNotify." + error;
                    }
                }
                return null;
            };

            /**
             * Creates a MsgBody message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.message.MsgBody
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.message.MsgBody} MsgBody
             */
            MsgBody.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.message.MsgBody)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.message.MsgBody: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.message.MsgBody();
                switch (object.cmd) {
                default:
                    if (typeof object.cmd === "number") {
                        message.cmd = object.cmd;
                        break;
                    }
                    break;
                case "CMD_UNKNOWN":
                case 0:
                    message.cmd = 0;
                    break;
                case "CMD_AUTH_REQ":
                case 1:
                    message.cmd = 1;
                    break;
                case "CMD_AUTH_RESP":
                case 2:
                    message.cmd = 2;
                    break;
                case "CMD_LOGOUT_REQ":
                case 3:
                    message.cmd = 3;
                    break;
                case "CMD_LOGOUT_RESP":
                case 4:
                    message.cmd = 4;
                    break;
                case "CMD_C2C_REQ":
                case 16:
                    message.cmd = 16;
                    break;
                case "CMD_C2C_RESP":
                case 17:
                    message.cmd = 17;
                    break;
                case "CMD_C2C_NOTIFY":
                case 18:
                    message.cmd = 18;
                    break;
                case "CMD_C2G_REQ":
                case 32:
                    message.cmd = 32;
                    break;
                case "CMD_C2G_RESP":
                case 33:
                    message.cmd = 33;
                    break;
                case "CMD_C2G_NOTIFY":
                case 34:
                    message.cmd = 34;
                    break;
                case "CMD_PULL_REQ":
                case 48:
                    message.cmd = 48;
                    break;
                case "CMD_PULL_RESP":
                case 49:
                    message.cmd = 49;
                    break;
                case "CMD_CTRL_REQ":
                case 64:
                    message.cmd = 64;
                    break;
                case "CMD_CTRL_RESP":
                case 65:
                    message.cmd = 65;
                    break;
                case "CMD_CTRL_NOTIFY":
                case 66:
                    message.cmd = 66;
                    break;
                case "CMD_PING":
                case 80:
                    message.cmd = 80;
                    break;
                case "CMD_PONG":
                case 81:
                    message.cmd = 81;
                    break;
                case "CMD_ACK_REQ":
                case 82:
                    message.cmd = 82;
                    break;
                case "CMD_ACK_RESP":
                case 83:
                    message.cmd = 83;
                    break;
                case "CMD_ACK_NOTIFY":
                case 84:
                    message.cmd = 84;
                    break;
                case "CMD_FRIEND_SEARCH_REQ":
                case 96:
                    message.cmd = 96;
                    break;
                case "CMD_FRIEND_SEARCH_RESP":
                case 97:
                    message.cmd = 97;
                    break;
                case "CMD_FRIEND_ADD_REQ":
                case 98:
                    message.cmd = 98;
                    break;
                case "CMD_FRIEND_ADD_RESP":
                case 99:
                    message.cmd = 99;
                    break;
                case "CMD_FRIEND_ADD_NOTIFY":
                case 100:
                    message.cmd = 100;
                    break;
                case "CMD_FRIEND_ACCEPT_REQ":
                case 101:
                    message.cmd = 101;
                    break;
                case "CMD_FRIEND_ACCEPT_RESP":
                case 102:
                    message.cmd = 102;
                    break;
                case "CMD_FRIEND_ACCEPT_NOTIFY":
                case 103:
                    message.cmd = 103;
                    break;
                case "CMD_FRIEND_DELETE_REQ":
                case 104:
                    message.cmd = 104;
                    break;
                case "CMD_FRIEND_DELETE_RESP":
                case 105:
                    message.cmd = 105;
                    break;
                case "CMD_FRIEND_DELETE_NOTIFY":
                case 106:
                    message.cmd = 106;
                    break;
                case "CMD_ERROR":
                case 65535:
                    message.cmd = 65535;
                    break;
                }
                if (object.authReq != null) {
                    if (!$util.isObject(object.authReq))
                        throw TypeError(".im.message.MsgBody.authReq: object expected");
                    message.authReq = $root.im.auth.AuthReq.fromObject(object.authReq, long + 1);
                }
                if (object.authResp != null) {
                    if (!$util.isObject(object.authResp))
                        throw TypeError(".im.message.MsgBody.authResp: object expected");
                    message.authResp = $root.im.auth.AuthResp.fromObject(object.authResp, long + 1);
                }
                if (object.logoutReq != null) {
                    if (!$util.isObject(object.logoutReq))
                        throw TypeError(".im.message.MsgBody.logoutReq: object expected");
                    message.logoutReq = $root.im.auth.LogoutReq.fromObject(object.logoutReq, long + 1);
                }
                if (object.logoutResp != null) {
                    if (!$util.isObject(object.logoutResp))
                        throw TypeError(".im.message.MsgBody.logoutResp: object expected");
                    message.logoutResp = $root.im.auth.LogoutResp.fromObject(object.logoutResp, long + 1);
                }
                if (object.c2cReq != null) {
                    if (!$util.isObject(object.c2cReq))
                        throw TypeError(".im.message.MsgBody.c2cReq: object expected");
                    message.c2cReq = $root.im.chat.C2CReq.fromObject(object.c2cReq, long + 1);
                }
                if (object.c2cResp != null) {
                    if (!$util.isObject(object.c2cResp))
                        throw TypeError(".im.message.MsgBody.c2cResp: object expected");
                    message.c2cResp = $root.im.chat.C2CResp.fromObject(object.c2cResp, long + 1);
                }
                if (object.c2cNotify != null) {
                    if (!$util.isObject(object.c2cNotify))
                        throw TypeError(".im.message.MsgBody.c2cNotify: object expected");
                    message.c2cNotify = $root.im.chat.C2CNotify.fromObject(object.c2cNotify, long + 1);
                }
                if (object.c2gReq != null) {
                    if (!$util.isObject(object.c2gReq))
                        throw TypeError(".im.message.MsgBody.c2gReq: object expected");
                    message.c2gReq = $root.im.group.C2GReq.fromObject(object.c2gReq, long + 1);
                }
                if (object.c2gResp != null) {
                    if (!$util.isObject(object.c2gResp))
                        throw TypeError(".im.message.MsgBody.c2gResp: object expected");
                    message.c2gResp = $root.im.group.C2GResp.fromObject(object.c2gResp, long + 1);
                }
                if (object.c2gNotify != null) {
                    if (!$util.isObject(object.c2gNotify))
                        throw TypeError(".im.message.MsgBody.c2gNotify: object expected");
                    message.c2gNotify = $root.im.group.C2GNotify.fromObject(object.c2gNotify, long + 1);
                }
                if (object.pullReq != null) {
                    if (!$util.isObject(object.pullReq))
                        throw TypeError(".im.message.MsgBody.pullReq: object expected");
                    message.pullReq = $root.im.pull.PullReq.fromObject(object.pullReq, long + 1);
                }
                if (object.pullResp != null) {
                    if (!$util.isObject(object.pullResp))
                        throw TypeError(".im.message.MsgBody.pullResp: object expected");
                    message.pullResp = $root.im.pull.PullResp.fromObject(object.pullResp, long + 1);
                }
                if (object.ctrlReq != null) {
                    if (!$util.isObject(object.ctrlReq))
                        throw TypeError(".im.message.MsgBody.ctrlReq: object expected");
                    message.ctrlReq = $root.im.ctrl.CtrlReq.fromObject(object.ctrlReq, long + 1);
                }
                if (object.ctrlResp != null) {
                    if (!$util.isObject(object.ctrlResp))
                        throw TypeError(".im.message.MsgBody.ctrlResp: object expected");
                    message.ctrlResp = $root.im.ctrl.CtrlResp.fromObject(object.ctrlResp, long + 1);
                }
                if (object.ctrlPush != null) {
                    if (!$util.isObject(object.ctrlPush))
                        throw TypeError(".im.message.MsgBody.ctrlPush: object expected");
                    message.ctrlPush = $root.im.ctrl.CtrlNotify.fromObject(object.ctrlPush, long + 1);
                }
                if (object.ping != null) {
                    if (!$util.isObject(object.ping))
                        throw TypeError(".im.message.MsgBody.ping: object expected");
                    message.ping = $root.im.heartbeat.Ping.fromObject(object.ping, long + 1);
                }
                if (object.pong != null) {
                    if (!$util.isObject(object.pong))
                        throw TypeError(".im.message.MsgBody.pong: object expected");
                    message.pong = $root.im.heartbeat.Pong.fromObject(object.pong, long + 1);
                }
                if (object.ackReq != null) {
                    if (!$util.isObject(object.ackReq))
                        throw TypeError(".im.message.MsgBody.ackReq: object expected");
                    message.ackReq = $root.im.ack.AckReq.fromObject(object.ackReq, long + 1);
                }
                if (object.ackResp != null) {
                    if (!$util.isObject(object.ackResp))
                        throw TypeError(".im.message.MsgBody.ackResp: object expected");
                    message.ackResp = $root.im.ack.AckResp.fromObject(object.ackResp, long + 1);
                }
                if (object.ackNotify != null) {
                    if (!$util.isObject(object.ackNotify))
                        throw TypeError(".im.message.MsgBody.ackNotify: object expected");
                    message.ackNotify = $root.im.ack.AckNotify.fromObject(object.ackNotify, long + 1);
                }
                return message;
            };

            /**
             * Creates a plain object from a MsgBody message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.message.MsgBody
             * @static
             * @param {im.message.MsgBody} message MsgBody
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            MsgBody.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults)
                    object.cmd = options.enums === String ? "CMD_UNKNOWN" : 0;
                if (message.cmd != null && Object.hasOwnProperty.call(message, "cmd"))
                    object.cmd = options.enums === String ? $root.im.common.Cmd[message.cmd] === undefined ? message.cmd : $root.im.common.Cmd[message.cmd] : message.cmd;
                if (message.authReq != null && Object.hasOwnProperty.call(message, "authReq")) {
                    object.authReq = $root.im.auth.AuthReq.toObject(message.authReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "authReq";
                }
                if (message.authResp != null && Object.hasOwnProperty.call(message, "authResp")) {
                    object.authResp = $root.im.auth.AuthResp.toObject(message.authResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "authResp";
                }
                if (message.logoutReq != null && Object.hasOwnProperty.call(message, "logoutReq")) {
                    object.logoutReq = $root.im.auth.LogoutReq.toObject(message.logoutReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "logoutReq";
                }
                if (message.logoutResp != null && Object.hasOwnProperty.call(message, "logoutResp")) {
                    object.logoutResp = $root.im.auth.LogoutResp.toObject(message.logoutResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "logoutResp";
                }
                if (message.c2cReq != null && Object.hasOwnProperty.call(message, "c2cReq")) {
                    object.c2cReq = $root.im.chat.C2CReq.toObject(message.c2cReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "c2cReq";
                }
                if (message.c2cResp != null && Object.hasOwnProperty.call(message, "c2cResp")) {
                    object.c2cResp = $root.im.chat.C2CResp.toObject(message.c2cResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "c2cResp";
                }
                if (message.c2cNotify != null && Object.hasOwnProperty.call(message, "c2cNotify")) {
                    object.c2cNotify = $root.im.chat.C2CNotify.toObject(message.c2cNotify, options, q + 1);
                    if (options.oneofs)
                        object.body = "c2cNotify";
                }
                if (message.c2gReq != null && Object.hasOwnProperty.call(message, "c2gReq")) {
                    object.c2gReq = $root.im.group.C2GReq.toObject(message.c2gReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "c2gReq";
                }
                if (message.c2gResp != null && Object.hasOwnProperty.call(message, "c2gResp")) {
                    object.c2gResp = $root.im.group.C2GResp.toObject(message.c2gResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "c2gResp";
                }
                if (message.c2gNotify != null && Object.hasOwnProperty.call(message, "c2gNotify")) {
                    object.c2gNotify = $root.im.group.C2GNotify.toObject(message.c2gNotify, options, q + 1);
                    if (options.oneofs)
                        object.body = "c2gNotify";
                }
                if (message.pullReq != null && Object.hasOwnProperty.call(message, "pullReq")) {
                    object.pullReq = $root.im.pull.PullReq.toObject(message.pullReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "pullReq";
                }
                if (message.pullResp != null && Object.hasOwnProperty.call(message, "pullResp")) {
                    object.pullResp = $root.im.pull.PullResp.toObject(message.pullResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "pullResp";
                }
                if (message.ctrlReq != null && Object.hasOwnProperty.call(message, "ctrlReq")) {
                    object.ctrlReq = $root.im.ctrl.CtrlReq.toObject(message.ctrlReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "ctrlReq";
                }
                if (message.ctrlResp != null && Object.hasOwnProperty.call(message, "ctrlResp")) {
                    object.ctrlResp = $root.im.ctrl.CtrlResp.toObject(message.ctrlResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "ctrlResp";
                }
                if (message.ctrlPush != null && Object.hasOwnProperty.call(message, "ctrlPush")) {
                    object.ctrlPush = $root.im.ctrl.CtrlNotify.toObject(message.ctrlPush, options, q + 1);
                    if (options.oneofs)
                        object.body = "ctrlPush";
                }
                if (message.ping != null && Object.hasOwnProperty.call(message, "ping")) {
                    object.ping = $root.im.heartbeat.Ping.toObject(message.ping, options, q + 1);
                    if (options.oneofs)
                        object.body = "ping";
                }
                if (message.pong != null && Object.hasOwnProperty.call(message, "pong")) {
                    object.pong = $root.im.heartbeat.Pong.toObject(message.pong, options, q + 1);
                    if (options.oneofs)
                        object.body = "pong";
                }
                if (message.ackReq != null && Object.hasOwnProperty.call(message, "ackReq")) {
                    object.ackReq = $root.im.ack.AckReq.toObject(message.ackReq, options, q + 1);
                    if (options.oneofs)
                        object.body = "ackReq";
                }
                if (message.ackResp != null && Object.hasOwnProperty.call(message, "ackResp")) {
                    object.ackResp = $root.im.ack.AckResp.toObject(message.ackResp, options, q + 1);
                    if (options.oneofs)
                        object.body = "ackResp";
                }
                if (message.ackNotify != null && Object.hasOwnProperty.call(message, "ackNotify")) {
                    object.ackNotify = $root.im.ack.AckNotify.toObject(message.ackNotify, options, q + 1);
                    if (options.oneofs)
                        object.body = "ackNotify";
                }
                return object;
            };

            /**
             * Converts this MsgBody to JSON.
             * @function toJSON
             * @memberof im.message.MsgBody
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            MsgBody.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for MsgBody
             * @function getTypeUrl
             * @memberof im.message.MsgBody
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            MsgBody.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.message.MsgBody";
            };

            return MsgBody;
        })();

        return message;
    })();

    im.pull = (function() {

        /**
         * Namespace pull.
         * @memberof im
         * @namespace
         */
        const pull = {};

        pull.PullReq = (function() {

            /**
             * Properties of a PullReq.
             * @memberof im.pull
             * @interface IPullReq
             * @property {number|null} [limit] PullReq limit
             * @property {number|Long|null} [lastMsgId] PullReq lastMsgId
             */

            /**
             * Constructs a new PullReq.
             * @memberof im.pull
             * @classdesc Represents a PullReq.
             * @implements IPullReq
             * @constructor
             * @param {im.pull.IPullReq=} [properties] Properties to set
             */
            function PullReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * PullReq limit.
             * @member {number} limit
             * @memberof im.pull.PullReq
             * @instance
             */
            PullReq.prototype.limit = 0;

            /**
             * PullReq lastMsgId.
             * @member {number|Long} lastMsgId
             * @memberof im.pull.PullReq
             * @instance
             */
            PullReq.prototype.lastMsgId = $util.Long ? $util.Long.fromBits(0,0,false) : 0;

            /**
             * Creates a new PullReq instance using the specified properties.
             * @function create
             * @memberof im.pull.PullReq
             * @static
             * @param {im.pull.IPullReq=} [properties] Properties to set
             * @returns {im.pull.PullReq} PullReq instance
             */
            PullReq.create = function create(properties) {
                return new PullReq(properties);
            };

            /**
             * Encodes the specified PullReq message. Does not implicitly {@link im.pull.PullReq.verify|verify} messages.
             * @function encode
             * @memberof im.pull.PullReq
             * @static
             * @param {im.pull.IPullReq} message PullReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            PullReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.limit != null && Object.hasOwnProperty.call(message, "limit"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.limit);
                if (message.lastMsgId != null && Object.hasOwnProperty.call(message, "lastMsgId"))
                    writer.uint32(/* id 2, wireType 0 =*/16).int64(message.lastMsgId);
                return writer;
            };

            /**
             * Encodes the specified PullReq message, length delimited. Does not implicitly {@link im.pull.PullReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.pull.PullReq
             * @static
             * @param {im.pull.IPullReq} message PullReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            PullReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a PullReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.pull.PullReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.pull.PullReq} PullReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            PullReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.pull.PullReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.limit = reader.int32();
                            break;
                        }
                    case 2: {
                            message.lastMsgId = reader.int64();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a PullReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.pull.PullReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.pull.PullReq} PullReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            PullReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a PullReq message.
             * @function verify
             * @memberof im.pull.PullReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            PullReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.limit != null && Object.hasOwnProperty.call(message, "limit"))
                    if (!$util.isInteger(message.limit))
                        return "limit: integer expected";
                if (message.lastMsgId != null && Object.hasOwnProperty.call(message, "lastMsgId"))
                    if (!$util.isInteger(message.lastMsgId) && !(message.lastMsgId && $util.isInteger(message.lastMsgId.low) && $util.isInteger(message.lastMsgId.high)))
                        return "lastMsgId: integer|Long expected";
                return null;
            };

            /**
             * Creates a PullReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.pull.PullReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.pull.PullReq} PullReq
             */
            PullReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.pull.PullReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.pull.PullReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.pull.PullReq();
                if (object.limit != null)
                    message.limit = object.limit | 0;
                if (object.lastMsgId != null)
                    if ($util.Long)
                        message.lastMsgId = $util.Long.fromValue(object.lastMsgId, false);
                    else if (typeof object.lastMsgId === "string")
                        message.lastMsgId = parseInt(object.lastMsgId, 10);
                    else if (typeof object.lastMsgId === "number")
                        message.lastMsgId = object.lastMsgId;
                    else if (typeof object.lastMsgId === "object")
                        message.lastMsgId = new $util.LongBits(object.lastMsgId.low >>> 0, object.lastMsgId.high >>> 0).toNumber();
                return message;
            };

            /**
             * Creates a plain object from a PullReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.pull.PullReq
             * @static
             * @param {im.pull.PullReq} message PullReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            PullReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.limit = 0;
                    if ($util.Long) {
                        let long = new $util.Long(0, 0, false);
                        object.lastMsgId = options.longs === String ? long.toString() : options.longs === Number ? long.toNumber() : typeof BigInt !== "undefined" && options.longs === BigInt ? long.toBigInt() : long;
                    } else
                        object.lastMsgId = options.longs === String ? "0" : typeof BigInt !== "undefined" && options.longs === BigInt ? BigInt("0") : 0;
                }
                if (message.limit != null && Object.hasOwnProperty.call(message, "limit"))
                    object.limit = message.limit;
                if (message.lastMsgId != null && Object.hasOwnProperty.call(message, "lastMsgId"))
                    if (typeof BigInt !== "undefined" && options.longs === BigInt)
                        object.lastMsgId = typeof message.lastMsgId === "number" ? BigInt(message.lastMsgId) : $util.Long.fromBits(message.lastMsgId.low >>> 0, message.lastMsgId.high >>> 0, false).toBigInt();
                    else if (typeof message.lastMsgId === "number")
                        object.lastMsgId = options.longs === String ? String(message.lastMsgId) : message.lastMsgId;
                    else
                        object.lastMsgId = options.longs === String ? $util.Long.prototype.toString.call(message.lastMsgId) : options.longs === Number ? new $util.LongBits(message.lastMsgId.low >>> 0, message.lastMsgId.high >>> 0).toNumber() : message.lastMsgId;
                return object;
            };

            /**
             * Converts this PullReq to JSON.
             * @function toJSON
             * @memberof im.pull.PullReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            PullReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for PullReq
             * @function getTypeUrl
             * @memberof im.pull.PullReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            PullReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.pull.PullReq";
            };

            return PullReq;
        })();

        pull.PullResp = (function() {

            /**
             * Properties of a PullResp.
             * @memberof im.pull
             * @interface IPullResp
             * @property {number|null} [code] PullResp code
             * @property {string|null} [message] PullResp message
             * @property {Array.<im.common.IMessageContent>|null} [messages] PullResp messages
             * @property {boolean|null} [hasMore] PullResp hasMore
             */

            /**
             * Constructs a new PullResp.
             * @memberof im.pull
             * @classdesc Represents a PullResp.
             * @implements IPullResp
             * @constructor
             * @param {im.pull.IPullResp=} [properties] Properties to set
             */
            function PullResp(properties) {
                this.messages = [];
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * PullResp code.
             * @member {number} code
             * @memberof im.pull.PullResp
             * @instance
             */
            PullResp.prototype.code = 0;

            /**
             * PullResp message.
             * @member {string} message
             * @memberof im.pull.PullResp
             * @instance
             */
            PullResp.prototype.message = "";

            /**
             * PullResp messages.
             * @member {Array.<im.common.IMessageContent>} messages
             * @memberof im.pull.PullResp
             * @instance
             */
            PullResp.prototype.messages = $util.emptyArray;

            /**
             * PullResp hasMore.
             * @member {boolean} hasMore
             * @memberof im.pull.PullResp
             * @instance
             */
            PullResp.prototype.hasMore = false;

            /**
             * Creates a new PullResp instance using the specified properties.
             * @function create
             * @memberof im.pull.PullResp
             * @static
             * @param {im.pull.IPullResp=} [properties] Properties to set
             * @returns {im.pull.PullResp} PullResp instance
             */
            PullResp.create = function create(properties) {
                return new PullResp(properties);
            };

            /**
             * Encodes the specified PullResp message. Does not implicitly {@link im.pull.PullResp.verify|verify} messages.
             * @function encode
             * @memberof im.pull.PullResp
             * @static
             * @param {im.pull.IPullResp} message PullResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            PullResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                if (message.messages != null && message.messages.length)
                    for (let i = 0; i < message.messages.length; ++i)
                        $root.im.common.MessageContent.encode(message.messages[i], writer.uint32(/* id 3, wireType 2 =*/26).fork(), q + 1).ldelim();
                if (message.hasMore != null && Object.hasOwnProperty.call(message, "hasMore"))
                    writer.uint32(/* id 4, wireType 0 =*/32).bool(message.hasMore);
                return writer;
            };

            /**
             * Encodes the specified PullResp message, length delimited. Does not implicitly {@link im.pull.PullResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.pull.PullResp
             * @static
             * @param {im.pull.IPullResp} message PullResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            PullResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a PullResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.pull.PullResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.pull.PullResp} PullResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            PullResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.pull.PullResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    case 3: {
                            if (!(message.messages && message.messages.length))
                                message.messages = [];
                            message.messages.push($root.im.common.MessageContent.decode(reader, reader.uint32(), undefined, long + 1));
                            break;
                        }
                    case 4: {
                            message.hasMore = reader.bool();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a PullResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.pull.PullResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.pull.PullResp} PullResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            PullResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a PullResp message.
             * @function verify
             * @memberof im.pull.PullResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            PullResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                if (message.messages != null && Object.hasOwnProperty.call(message, "messages")) {
                    if (!Array.isArray(message.messages))
                        return "messages: array expected";
                    for (let i = 0; i < message.messages.length; ++i) {
                        let error = $root.im.common.MessageContent.verify(message.messages[i], long + 1);
                        if (error)
                            return "messages." + error;
                    }
                }
                if (message.hasMore != null && Object.hasOwnProperty.call(message, "hasMore"))
                    if (typeof message.hasMore !== "boolean")
                        return "hasMore: boolean expected";
                return null;
            };

            /**
             * Creates a PullResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.pull.PullResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.pull.PullResp} PullResp
             */
            PullResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.pull.PullResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.pull.PullResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.pull.PullResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                if (object.messages) {
                    if (!Array.isArray(object.messages))
                        throw TypeError(".im.pull.PullResp.messages: array expected");
                    message.messages = [];
                    for (let i = 0; i < object.messages.length; ++i) {
                        if (!$util.isObject(object.messages[i]))
                            throw TypeError(".im.pull.PullResp.messages: object expected");
                        message.messages[i] = $root.im.common.MessageContent.fromObject(object.messages[i], long + 1);
                    }
                }
                if (object.hasMore != null)
                    message.hasMore = Boolean(object.hasMore);
                return message;
            };

            /**
             * Creates a plain object from a PullResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.pull.PullResp
             * @static
             * @param {im.pull.PullResp} message PullResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            PullResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.arrays || options.defaults)
                    object.messages = [];
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                    object.hasMore = false;
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                if (message.messages && message.messages.length) {
                    object.messages = [];
                    for (let j = 0; j < message.messages.length; ++j)
                        object.messages[j] = $root.im.common.MessageContent.toObject(message.messages[j], options, q + 1);
                }
                if (message.hasMore != null && Object.hasOwnProperty.call(message, "hasMore"))
                    object.hasMore = message.hasMore;
                return object;
            };

            /**
             * Converts this PullResp to JSON.
             * @function toJSON
             * @memberof im.pull.PullResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            PullResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for PullResp
             * @function getTypeUrl
             * @memberof im.pull.PullResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            PullResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.pull.PullResp";
            };

            return PullResp;
        })();

        return pull;
    })();

    im.relation = (function() {

        /**
         * Namespace relation.
         * @memberof im
         * @namespace
         */
        const relation = {};

        relation.SearchUserReq = (function() {

            /**
             * Properties of a SearchUserReq.
             * @memberof im.relation
             * @interface ISearchUserReq
             * @property {string|null} [keyword] SearchUserReq keyword
             */

            /**
             * Constructs a new SearchUserReq.
             * @memberof im.relation
             * @classdesc Represents a SearchUserReq.
             * @implements ISearchUserReq
             * @constructor
             * @param {im.relation.ISearchUserReq=} [properties] Properties to set
             */
            function SearchUserReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * SearchUserReq keyword.
             * @member {string} keyword
             * @memberof im.relation.SearchUserReq
             * @instance
             */
            SearchUserReq.prototype.keyword = "";

            /**
             * Creates a new SearchUserReq instance using the specified properties.
             * @function create
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {im.relation.ISearchUserReq=} [properties] Properties to set
             * @returns {im.relation.SearchUserReq} SearchUserReq instance
             */
            SearchUserReq.create = function create(properties) {
                return new SearchUserReq(properties);
            };

            /**
             * Encodes the specified SearchUserReq message. Does not implicitly {@link im.relation.SearchUserReq.verify|verify} messages.
             * @function encode
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {im.relation.ISearchUserReq} message SearchUserReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            SearchUserReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.keyword != null && Object.hasOwnProperty.call(message, "keyword"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.keyword);
                return writer;
            };

            /**
             * Encodes the specified SearchUserReq message, length delimited. Does not implicitly {@link im.relation.SearchUserReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {im.relation.ISearchUserReq} message SearchUserReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            SearchUserReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a SearchUserReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.SearchUserReq} SearchUserReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            SearchUserReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.SearchUserReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.keyword = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a SearchUserReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.SearchUserReq} SearchUserReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            SearchUserReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a SearchUserReq message.
             * @function verify
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            SearchUserReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.keyword != null && Object.hasOwnProperty.call(message, "keyword"))
                    if (!$util.isString(message.keyword))
                        return "keyword: string expected";
                return null;
            };

            /**
             * Creates a SearchUserReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.SearchUserReq} SearchUserReq
             */
            SearchUserReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.SearchUserReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.SearchUserReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.SearchUserReq();
                if (object.keyword != null)
                    message.keyword = String(object.keyword);
                return message;
            };

            /**
             * Creates a plain object from a SearchUserReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {im.relation.SearchUserReq} message SearchUserReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            SearchUserReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults)
                    object.keyword = "";
                if (message.keyword != null && Object.hasOwnProperty.call(message, "keyword"))
                    object.keyword = message.keyword;
                return object;
            };

            /**
             * Converts this SearchUserReq to JSON.
             * @function toJSON
             * @memberof im.relation.SearchUserReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            SearchUserReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for SearchUserReq
             * @function getTypeUrl
             * @memberof im.relation.SearchUserReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            SearchUserReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.SearchUserReq";
            };

            return SearchUserReq;
        })();

        relation.SearchUserResp = (function() {

            /**
             * Properties of a SearchUserResp.
             * @memberof im.relation
             * @interface ISearchUserResp
             * @property {number|null} [code] SearchUserResp code
             * @property {string|null} [message] SearchUserResp message
             * @property {Array.<im.relation.SearchUserResp.IUserInfo>|null} [users] SearchUserResp users
             */

            /**
             * Constructs a new SearchUserResp.
             * @memberof im.relation
             * @classdesc Represents a SearchUserResp.
             * @implements ISearchUserResp
             * @constructor
             * @param {im.relation.ISearchUserResp=} [properties] Properties to set
             */
            function SearchUserResp(properties) {
                this.users = [];
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * SearchUserResp code.
             * @member {number} code
             * @memberof im.relation.SearchUserResp
             * @instance
             */
            SearchUserResp.prototype.code = 0;

            /**
             * SearchUserResp message.
             * @member {string} message
             * @memberof im.relation.SearchUserResp
             * @instance
             */
            SearchUserResp.prototype.message = "";

            /**
             * SearchUserResp users.
             * @member {Array.<im.relation.SearchUserResp.IUserInfo>} users
             * @memberof im.relation.SearchUserResp
             * @instance
             */
            SearchUserResp.prototype.users = $util.emptyArray;

            /**
             * Creates a new SearchUserResp instance using the specified properties.
             * @function create
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {im.relation.ISearchUserResp=} [properties] Properties to set
             * @returns {im.relation.SearchUserResp} SearchUserResp instance
             */
            SearchUserResp.create = function create(properties) {
                return new SearchUserResp(properties);
            };

            /**
             * Encodes the specified SearchUserResp message. Does not implicitly {@link im.relation.SearchUserResp.verify|verify} messages.
             * @function encode
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {im.relation.ISearchUserResp} message SearchUserResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            SearchUserResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                if (message.users != null && message.users.length)
                    for (let i = 0; i < message.users.length; ++i)
                        $root.im.relation.SearchUserResp.UserInfo.encode(message.users[i], writer.uint32(/* id 3, wireType 2 =*/26).fork(), q + 1).ldelim();
                return writer;
            };

            /**
             * Encodes the specified SearchUserResp message, length delimited. Does not implicitly {@link im.relation.SearchUserResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {im.relation.ISearchUserResp} message SearchUserResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            SearchUserResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a SearchUserResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.SearchUserResp} SearchUserResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            SearchUserResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.SearchUserResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    case 3: {
                            if (!(message.users && message.users.length))
                                message.users = [];
                            message.users.push($root.im.relation.SearchUserResp.UserInfo.decode(reader, reader.uint32(), undefined, long + 1));
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a SearchUserResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.SearchUserResp} SearchUserResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            SearchUserResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a SearchUserResp message.
             * @function verify
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            SearchUserResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                if (message.users != null && Object.hasOwnProperty.call(message, "users")) {
                    if (!Array.isArray(message.users))
                        return "users: array expected";
                    for (let i = 0; i < message.users.length; ++i) {
                        let error = $root.im.relation.SearchUserResp.UserInfo.verify(message.users[i], long + 1);
                        if (error)
                            return "users." + error;
                    }
                }
                return null;
            };

            /**
             * Creates a SearchUserResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.SearchUserResp} SearchUserResp
             */
            SearchUserResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.SearchUserResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.SearchUserResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.SearchUserResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                if (object.users) {
                    if (!Array.isArray(object.users))
                        throw TypeError(".im.relation.SearchUserResp.users: array expected");
                    message.users = [];
                    for (let i = 0; i < object.users.length; ++i) {
                        if (!$util.isObject(object.users[i]))
                            throw TypeError(".im.relation.SearchUserResp.users: object expected");
                        message.users[i] = $root.im.relation.SearchUserResp.UserInfo.fromObject(object.users[i], long + 1);
                    }
                }
                return message;
            };

            /**
             * Creates a plain object from a SearchUserResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {im.relation.SearchUserResp} message SearchUserResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            SearchUserResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.arrays || options.defaults)
                    object.users = [];
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                if (message.users && message.users.length) {
                    object.users = [];
                    for (let j = 0; j < message.users.length; ++j)
                        object.users[j] = $root.im.relation.SearchUserResp.UserInfo.toObject(message.users[j], options, q + 1);
                }
                return object;
            };

            /**
             * Converts this SearchUserResp to JSON.
             * @function toJSON
             * @memberof im.relation.SearchUserResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            SearchUserResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for SearchUserResp
             * @function getTypeUrl
             * @memberof im.relation.SearchUserResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            SearchUserResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.SearchUserResp";
            };

            SearchUserResp.UserInfo = (function() {

                /**
                 * Properties of a UserInfo.
                 * @memberof im.relation.SearchUserResp
                 * @interface IUserInfo
                 * @property {string|null} [userId] UserInfo userId
                 * @property {string|null} [userName] UserInfo userName
                 * @property {string|null} [nickname] UserInfo nickname
                 * @property {string|null} [avatar] UserInfo avatar
                 */

                /**
                 * Constructs a new UserInfo.
                 * @memberof im.relation.SearchUserResp
                 * @classdesc Represents a UserInfo.
                 * @implements IUserInfo
                 * @constructor
                 * @param {im.relation.SearchUserResp.IUserInfo=} [properties] Properties to set
                 */
                function UserInfo(properties) {
                    if (properties)
                        for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                            if (properties[keys[i]] != null && keys[i] !== "__proto__")
                                this[keys[i]] = properties[keys[i]];
                }

                /**
                 * UserInfo userId.
                 * @member {string} userId
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @instance
                 */
                UserInfo.prototype.userId = "";

                /**
                 * UserInfo userName.
                 * @member {string} userName
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @instance
                 */
                UserInfo.prototype.userName = "";

                /**
                 * UserInfo nickname.
                 * @member {string} nickname
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @instance
                 */
                UserInfo.prototype.nickname = "";

                /**
                 * UserInfo avatar.
                 * @member {string} avatar
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @instance
                 */
                UserInfo.prototype.avatar = "";

                /**
                 * Creates a new UserInfo instance using the specified properties.
                 * @function create
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {im.relation.SearchUserResp.IUserInfo=} [properties] Properties to set
                 * @returns {im.relation.SearchUserResp.UserInfo} UserInfo instance
                 */
                UserInfo.create = function create(properties) {
                    return new UserInfo(properties);
                };

                /**
                 * Encodes the specified UserInfo message. Does not implicitly {@link im.relation.SearchUserResp.UserInfo.verify|verify} messages.
                 * @function encode
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {im.relation.SearchUserResp.IUserInfo} message UserInfo message or plain object to encode
                 * @param {$protobuf.Writer} [writer] Writer to encode to
                 * @returns {$protobuf.Writer} Writer
                 */
                UserInfo.encode = function encode(message, writer, q) {
                    if (!writer)
                        writer = $Writer.create();
                    if (q === undefined)
                        q = 0;
                    if (q > $util.recursionLimit)
                        throw Error("max depth exceeded");
                    if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                        writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                    if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                        writer.uint32(/* id 2, wireType 2 =*/18).string(message.userName);
                    if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                        writer.uint32(/* id 3, wireType 2 =*/26).string(message.nickname);
                    if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                        writer.uint32(/* id 4, wireType 2 =*/34).string(message.avatar);
                    return writer;
                };

                /**
                 * Encodes the specified UserInfo message, length delimited. Does not implicitly {@link im.relation.SearchUserResp.UserInfo.verify|verify} messages.
                 * @function encodeDelimited
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {im.relation.SearchUserResp.IUserInfo} message UserInfo message or plain object to encode
                 * @param {$protobuf.Writer} [writer] Writer to encode to
                 * @returns {$protobuf.Writer} Writer
                 */
                UserInfo.encodeDelimited = function encodeDelimited(message, writer) {
                    return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
                };

                /**
                 * Decodes a UserInfo message from the specified reader or buffer.
                 * @function decode
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
                 * @param {number} [length] Message length if known beforehand
                 * @returns {im.relation.SearchUserResp.UserInfo} UserInfo
                 * @throws {Error} If the payload is not a reader or valid buffer
                 * @throws {$protobuf.util.ProtocolError} If required fields are missing
                 */
                UserInfo.decode = function decode(reader, length, error, long) {
                    if (!(reader instanceof $Reader))
                        reader = $Reader.create(reader);
                    if (long === undefined)
                        long = 0;
                    if (long > $Reader.recursionLimit)
                        throw Error("maximum nesting depth exceeded");
                    let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.SearchUserResp.UserInfo();
                    while (reader.pos < end) {
                        let tag = reader.uint32();
                        if (tag === error)
                            break;
                        switch (tag >>> 3) {
                        case 1: {
                                message.userId = reader.string();
                                break;
                            }
                        case 2: {
                                message.userName = reader.string();
                                break;
                            }
                        case 3: {
                                message.nickname = reader.string();
                                break;
                            }
                        case 4: {
                                message.avatar = reader.string();
                                break;
                            }
                        default:
                            reader.skipType(tag & 7, long);
                            break;
                        }
                    }
                    return message;
                };

                /**
                 * Decodes a UserInfo message from the specified reader or buffer, length delimited.
                 * @function decodeDelimited
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
                 * @returns {im.relation.SearchUserResp.UserInfo} UserInfo
                 * @throws {Error} If the payload is not a reader or valid buffer
                 * @throws {$protobuf.util.ProtocolError} If required fields are missing
                 */
                UserInfo.decodeDelimited = function decodeDelimited(reader) {
                    if (!(reader instanceof $Reader))
                        reader = new $Reader(reader);
                    return this.decode(reader, reader.uint32());
                };

                /**
                 * Verifies a UserInfo message.
                 * @function verify
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {Object.<string,*>} message Plain object to verify
                 * @returns {string|null} `null` if valid, otherwise the reason why it is not
                 */
                UserInfo.verify = function verify(message, long) {
                    if (typeof message !== "object" || message === null)
                        return "object expected";
                    if (long === undefined)
                        long = 0;
                    if (long > $util.recursionLimit)
                        return "maximum nesting depth exceeded";
                    if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                        if (!$util.isString(message.userId))
                            return "userId: string expected";
                    if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                        if (!$util.isString(message.userName))
                            return "userName: string expected";
                    if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                        if (!$util.isString(message.nickname))
                            return "nickname: string expected";
                    if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                        if (!$util.isString(message.avatar))
                            return "avatar: string expected";
                    return null;
                };

                /**
                 * Creates a UserInfo message from a plain object. Also converts values to their respective internal types.
                 * @function fromObject
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {Object.<string,*>} object Plain object
                 * @returns {im.relation.SearchUserResp.UserInfo} UserInfo
                 */
                UserInfo.fromObject = function fromObject(object, long) {
                    if (object instanceof $root.im.relation.SearchUserResp.UserInfo)
                        return object;
                    if (!$util.isObject(object))
                        throw TypeError(".im.relation.SearchUserResp.UserInfo: object expected");
                    if (long === undefined)
                        long = 0;
                    if (long > $util.recursionLimit)
                        throw Error("maximum nesting depth exceeded");
                    let message = new $root.im.relation.SearchUserResp.UserInfo();
                    if (object.userId != null)
                        message.userId = String(object.userId);
                    if (object.userName != null)
                        message.userName = String(object.userName);
                    if (object.nickname != null)
                        message.nickname = String(object.nickname);
                    if (object.avatar != null)
                        message.avatar = String(object.avatar);
                    return message;
                };

                /**
                 * Creates a plain object from a UserInfo message. Also converts values to other types if specified.
                 * @function toObject
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {im.relation.SearchUserResp.UserInfo} message UserInfo
                 * @param {$protobuf.IConversionOptions} [options] Conversion options
                 * @returns {Object.<string,*>} Plain object
                 */
                UserInfo.toObject = function toObject(message, options, q) {
                    if (!options)
                        options = {};
                    if (q === undefined)
                        q = 0;
                    if (q > $util.recursionLimit)
                        throw Error("max depth exceeded");
                    let object = {};
                    if (options.defaults) {
                        object.userId = "";
                        object.userName = "";
                        object.nickname = "";
                        object.avatar = "";
                    }
                    if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                        object.userId = message.userId;
                    if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                        object.userName = message.userName;
                    if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                        object.nickname = message.nickname;
                    if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                        object.avatar = message.avatar;
                    return object;
                };

                /**
                 * Converts this UserInfo to JSON.
                 * @function toJSON
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @instance
                 * @returns {Object.<string,*>} JSON object
                 */
                UserInfo.prototype.toJSON = function toJSON() {
                    return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
                };

                /**
                 * Gets the default type url for UserInfo
                 * @function getTypeUrl
                 * @memberof im.relation.SearchUserResp.UserInfo
                 * @static
                 * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
                 * @returns {string} The default type url
                 */
                UserInfo.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                    if (typeUrlPrefix === undefined) {
                        typeUrlPrefix = "type.googleapis.com";
                    }
                    return typeUrlPrefix + "/im.relation.SearchUserResp.UserInfo";
                };

                return UserInfo;
            })();

            return SearchUserResp;
        })();

        relation.FriendAddReq = (function() {

            /**
             * Properties of a FriendAddReq.
             * @memberof im.relation
             * @interface IFriendAddReq
             * @property {string|null} [userId] FriendAddReq userId
             * @property {string|null} [friendId] FriendAddReq friendId
             */

            /**
             * Constructs a new FriendAddReq.
             * @memberof im.relation
             * @classdesc Represents a FriendAddReq.
             * @implements IFriendAddReq
             * @constructor
             * @param {im.relation.IFriendAddReq=} [properties] Properties to set
             */
            function FriendAddReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendAddReq userId.
             * @member {string} userId
             * @memberof im.relation.FriendAddReq
             * @instance
             */
            FriendAddReq.prototype.userId = "";

            /**
             * FriendAddReq friendId.
             * @member {string} friendId
             * @memberof im.relation.FriendAddReq
             * @instance
             */
            FriendAddReq.prototype.friendId = "";

            /**
             * Creates a new FriendAddReq instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {im.relation.IFriendAddReq=} [properties] Properties to set
             * @returns {im.relation.FriendAddReq} FriendAddReq instance
             */
            FriendAddReq.create = function create(properties) {
                return new FriendAddReq(properties);
            };

            /**
             * Encodes the specified FriendAddReq message. Does not implicitly {@link im.relation.FriendAddReq.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {im.relation.IFriendAddReq} message FriendAddReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAddReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.friendId);
                return writer;
            };

            /**
             * Encodes the specified FriendAddReq message, length delimited. Does not implicitly {@link im.relation.FriendAddReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {im.relation.IFriendAddReq} message FriendAddReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAddReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendAddReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendAddReq} FriendAddReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAddReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendAddReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.userId = reader.string();
                            break;
                        }
                    case 2: {
                            message.friendId = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendAddReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendAddReq} FriendAddReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAddReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendAddReq message.
             * @function verify
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendAddReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    if (!$util.isString(message.friendId))
                        return "friendId: string expected";
                return null;
            };

            /**
             * Creates a FriendAddReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendAddReq} FriendAddReq
             */
            FriendAddReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendAddReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendAddReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendAddReq();
                if (object.userId != null)
                    message.userId = String(object.userId);
                if (object.friendId != null)
                    message.friendId = String(object.friendId);
                return message;
            };

            /**
             * Creates a plain object from a FriendAddReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {im.relation.FriendAddReq} message FriendAddReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendAddReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.userId = "";
                    object.friendId = "";
                }
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    object.friendId = message.friendId;
                return object;
            };

            /**
             * Converts this FriendAddReq to JSON.
             * @function toJSON
             * @memberof im.relation.FriendAddReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendAddReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendAddReq
             * @function getTypeUrl
             * @memberof im.relation.FriendAddReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendAddReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendAddReq";
            };

            return FriendAddReq;
        })();

        relation.FriendAddResp = (function() {

            /**
             * Properties of a FriendAddResp.
             * @memberof im.relation
             * @interface IFriendAddResp
             * @property {number|null} [code] FriendAddResp code
             * @property {string|null} [message] FriendAddResp message
             */

            /**
             * Constructs a new FriendAddResp.
             * @memberof im.relation
             * @classdesc Represents a FriendAddResp.
             * @implements IFriendAddResp
             * @constructor
             * @param {im.relation.IFriendAddResp=} [properties] Properties to set
             */
            function FriendAddResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendAddResp code.
             * @member {number} code
             * @memberof im.relation.FriendAddResp
             * @instance
             */
            FriendAddResp.prototype.code = 0;

            /**
             * FriendAddResp message.
             * @member {string} message
             * @memberof im.relation.FriendAddResp
             * @instance
             */
            FriendAddResp.prototype.message = "";

            /**
             * Creates a new FriendAddResp instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {im.relation.IFriendAddResp=} [properties] Properties to set
             * @returns {im.relation.FriendAddResp} FriendAddResp instance
             */
            FriendAddResp.create = function create(properties) {
                return new FriendAddResp(properties);
            };

            /**
             * Encodes the specified FriendAddResp message. Does not implicitly {@link im.relation.FriendAddResp.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {im.relation.IFriendAddResp} message FriendAddResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAddResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                return writer;
            };

            /**
             * Encodes the specified FriendAddResp message, length delimited. Does not implicitly {@link im.relation.FriendAddResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {im.relation.IFriendAddResp} message FriendAddResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAddResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendAddResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendAddResp} FriendAddResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAddResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendAddResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendAddResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendAddResp} FriendAddResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAddResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendAddResp message.
             * @function verify
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendAddResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                return null;
            };

            /**
             * Creates a FriendAddResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendAddResp} FriendAddResp
             */
            FriendAddResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendAddResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendAddResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendAddResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                return message;
            };

            /**
             * Creates a plain object from a FriendAddResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {im.relation.FriendAddResp} message FriendAddResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendAddResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                return object;
            };

            /**
             * Converts this FriendAddResp to JSON.
             * @function toJSON
             * @memberof im.relation.FriendAddResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendAddResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendAddResp
             * @function getTypeUrl
             * @memberof im.relation.FriendAddResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendAddResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendAddResp";
            };

            return FriendAddResp;
        })();

        relation.FriendAddNotify = (function() {

            /**
             * Properties of a FriendAddNotify.
             * @memberof im.relation
             * @interface IFriendAddNotify
             * @property {string|null} [userId] FriendAddNotify userId
             * @property {string|null} [userName] FriendAddNotify userName
             * @property {string|null} [nickname] FriendAddNotify nickname
             * @property {string|null} [avatar] FriendAddNotify avatar
             */

            /**
             * Constructs a new FriendAddNotify.
             * @memberof im.relation
             * @classdesc Represents a FriendAddNotify.
             * @implements IFriendAddNotify
             * @constructor
             * @param {im.relation.IFriendAddNotify=} [properties] Properties to set
             */
            function FriendAddNotify(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendAddNotify userId.
             * @member {string} userId
             * @memberof im.relation.FriendAddNotify
             * @instance
             */
            FriendAddNotify.prototype.userId = "";

            /**
             * FriendAddNotify userName.
             * @member {string} userName
             * @memberof im.relation.FriendAddNotify
             * @instance
             */
            FriendAddNotify.prototype.userName = "";

            /**
             * FriendAddNotify nickname.
             * @member {string} nickname
             * @memberof im.relation.FriendAddNotify
             * @instance
             */
            FriendAddNotify.prototype.nickname = "";

            /**
             * FriendAddNotify avatar.
             * @member {string} avatar
             * @memberof im.relation.FriendAddNotify
             * @instance
             */
            FriendAddNotify.prototype.avatar = "";

            /**
             * Creates a new FriendAddNotify instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {im.relation.IFriendAddNotify=} [properties] Properties to set
             * @returns {im.relation.FriendAddNotify} FriendAddNotify instance
             */
            FriendAddNotify.create = function create(properties) {
                return new FriendAddNotify(properties);
            };

            /**
             * Encodes the specified FriendAddNotify message. Does not implicitly {@link im.relation.FriendAddNotify.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {im.relation.IFriendAddNotify} message FriendAddNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAddNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.userName);
                if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                    writer.uint32(/* id 3, wireType 2 =*/26).string(message.nickname);
                if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                    writer.uint32(/* id 4, wireType 2 =*/34).string(message.avatar);
                return writer;
            };

            /**
             * Encodes the specified FriendAddNotify message, length delimited. Does not implicitly {@link im.relation.FriendAddNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {im.relation.IFriendAddNotify} message FriendAddNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAddNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendAddNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendAddNotify} FriendAddNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAddNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendAddNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.userId = reader.string();
                            break;
                        }
                    case 2: {
                            message.userName = reader.string();
                            break;
                        }
                    case 3: {
                            message.nickname = reader.string();
                            break;
                        }
                    case 4: {
                            message.avatar = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendAddNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendAddNotify} FriendAddNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAddNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendAddNotify message.
             * @function verify
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendAddNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                    if (!$util.isString(message.userName))
                        return "userName: string expected";
                if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                    if (!$util.isString(message.nickname))
                        return "nickname: string expected";
                if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                    if (!$util.isString(message.avatar))
                        return "avatar: string expected";
                return null;
            };

            /**
             * Creates a FriendAddNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendAddNotify} FriendAddNotify
             */
            FriendAddNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendAddNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendAddNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendAddNotify();
                if (object.userId != null)
                    message.userId = String(object.userId);
                if (object.userName != null)
                    message.userName = String(object.userName);
                if (object.nickname != null)
                    message.nickname = String(object.nickname);
                if (object.avatar != null)
                    message.avatar = String(object.avatar);
                return message;
            };

            /**
             * Creates a plain object from a FriendAddNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {im.relation.FriendAddNotify} message FriendAddNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendAddNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.userId = "";
                    object.userName = "";
                    object.nickname = "";
                    object.avatar = "";
                }
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                    object.userName = message.userName;
                if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                    object.nickname = message.nickname;
                if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                    object.avatar = message.avatar;
                return object;
            };

            /**
             * Converts this FriendAddNotify to JSON.
             * @function toJSON
             * @memberof im.relation.FriendAddNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendAddNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendAddNotify
             * @function getTypeUrl
             * @memberof im.relation.FriendAddNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendAddNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendAddNotify";
            };

            return FriendAddNotify;
        })();

        relation.FriendAcceptReq = (function() {

            /**
             * Properties of a FriendAcceptReq.
             * @memberof im.relation
             * @interface IFriendAcceptReq
             * @property {string|null} [userId] FriendAcceptReq userId
             * @property {string|null} [friendId] FriendAcceptReq friendId
             */

            /**
             * Constructs a new FriendAcceptReq.
             * @memberof im.relation
             * @classdesc Represents a FriendAcceptReq.
             * @implements IFriendAcceptReq
             * @constructor
             * @param {im.relation.IFriendAcceptReq=} [properties] Properties to set
             */
            function FriendAcceptReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendAcceptReq userId.
             * @member {string} userId
             * @memberof im.relation.FriendAcceptReq
             * @instance
             */
            FriendAcceptReq.prototype.userId = "";

            /**
             * FriendAcceptReq friendId.
             * @member {string} friendId
             * @memberof im.relation.FriendAcceptReq
             * @instance
             */
            FriendAcceptReq.prototype.friendId = "";

            /**
             * Creates a new FriendAcceptReq instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {im.relation.IFriendAcceptReq=} [properties] Properties to set
             * @returns {im.relation.FriendAcceptReq} FriendAcceptReq instance
             */
            FriendAcceptReq.create = function create(properties) {
                return new FriendAcceptReq(properties);
            };

            /**
             * Encodes the specified FriendAcceptReq message. Does not implicitly {@link im.relation.FriendAcceptReq.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {im.relation.IFriendAcceptReq} message FriendAcceptReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAcceptReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.friendId);
                return writer;
            };

            /**
             * Encodes the specified FriendAcceptReq message, length delimited. Does not implicitly {@link im.relation.FriendAcceptReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {im.relation.IFriendAcceptReq} message FriendAcceptReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAcceptReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendAcceptReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendAcceptReq} FriendAcceptReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAcceptReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendAcceptReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.userId = reader.string();
                            break;
                        }
                    case 2: {
                            message.friendId = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendAcceptReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendAcceptReq} FriendAcceptReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAcceptReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendAcceptReq message.
             * @function verify
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendAcceptReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    if (!$util.isString(message.friendId))
                        return "friendId: string expected";
                return null;
            };

            /**
             * Creates a FriendAcceptReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendAcceptReq} FriendAcceptReq
             */
            FriendAcceptReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendAcceptReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendAcceptReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendAcceptReq();
                if (object.userId != null)
                    message.userId = String(object.userId);
                if (object.friendId != null)
                    message.friendId = String(object.friendId);
                return message;
            };

            /**
             * Creates a plain object from a FriendAcceptReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {im.relation.FriendAcceptReq} message FriendAcceptReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendAcceptReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.userId = "";
                    object.friendId = "";
                }
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    object.friendId = message.friendId;
                return object;
            };

            /**
             * Converts this FriendAcceptReq to JSON.
             * @function toJSON
             * @memberof im.relation.FriendAcceptReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendAcceptReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendAcceptReq
             * @function getTypeUrl
             * @memberof im.relation.FriendAcceptReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendAcceptReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendAcceptReq";
            };

            return FriendAcceptReq;
        })();

        relation.FriendAcceptResp = (function() {

            /**
             * Properties of a FriendAcceptResp.
             * @memberof im.relation
             * @interface IFriendAcceptResp
             * @property {number|null} [code] FriendAcceptResp code
             * @property {string|null} [message] FriendAcceptResp message
             */

            /**
             * Constructs a new FriendAcceptResp.
             * @memberof im.relation
             * @classdesc Represents a FriendAcceptResp.
             * @implements IFriendAcceptResp
             * @constructor
             * @param {im.relation.IFriendAcceptResp=} [properties] Properties to set
             */
            function FriendAcceptResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendAcceptResp code.
             * @member {number} code
             * @memberof im.relation.FriendAcceptResp
             * @instance
             */
            FriendAcceptResp.prototype.code = 0;

            /**
             * FriendAcceptResp message.
             * @member {string} message
             * @memberof im.relation.FriendAcceptResp
             * @instance
             */
            FriendAcceptResp.prototype.message = "";

            /**
             * Creates a new FriendAcceptResp instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {im.relation.IFriendAcceptResp=} [properties] Properties to set
             * @returns {im.relation.FriendAcceptResp} FriendAcceptResp instance
             */
            FriendAcceptResp.create = function create(properties) {
                return new FriendAcceptResp(properties);
            };

            /**
             * Encodes the specified FriendAcceptResp message. Does not implicitly {@link im.relation.FriendAcceptResp.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {im.relation.IFriendAcceptResp} message FriendAcceptResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAcceptResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                return writer;
            };

            /**
             * Encodes the specified FriendAcceptResp message, length delimited. Does not implicitly {@link im.relation.FriendAcceptResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {im.relation.IFriendAcceptResp} message FriendAcceptResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAcceptResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendAcceptResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendAcceptResp} FriendAcceptResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAcceptResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendAcceptResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendAcceptResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendAcceptResp} FriendAcceptResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAcceptResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendAcceptResp message.
             * @function verify
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendAcceptResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                return null;
            };

            /**
             * Creates a FriendAcceptResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendAcceptResp} FriendAcceptResp
             */
            FriendAcceptResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendAcceptResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendAcceptResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendAcceptResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                return message;
            };

            /**
             * Creates a plain object from a FriendAcceptResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {im.relation.FriendAcceptResp} message FriendAcceptResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendAcceptResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                return object;
            };

            /**
             * Converts this FriendAcceptResp to JSON.
             * @function toJSON
             * @memberof im.relation.FriendAcceptResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendAcceptResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendAcceptResp
             * @function getTypeUrl
             * @memberof im.relation.FriendAcceptResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendAcceptResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendAcceptResp";
            };

            return FriendAcceptResp;
        })();

        relation.FriendAcceptNotify = (function() {

            /**
             * Properties of a FriendAcceptNotify.
             * @memberof im.relation
             * @interface IFriendAcceptNotify
             * @property {string|null} [userId] FriendAcceptNotify userId
             * @property {string|null} [userName] FriendAcceptNotify userName
             * @property {string|null} [nickname] FriendAcceptNotify nickname
             * @property {string|null} [avatar] FriendAcceptNotify avatar
             */

            /**
             * Constructs a new FriendAcceptNotify.
             * @memberof im.relation
             * @classdesc Represents a FriendAcceptNotify.
             * @implements IFriendAcceptNotify
             * @constructor
             * @param {im.relation.IFriendAcceptNotify=} [properties] Properties to set
             */
            function FriendAcceptNotify(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendAcceptNotify userId.
             * @member {string} userId
             * @memberof im.relation.FriendAcceptNotify
             * @instance
             */
            FriendAcceptNotify.prototype.userId = "";

            /**
             * FriendAcceptNotify userName.
             * @member {string} userName
             * @memberof im.relation.FriendAcceptNotify
             * @instance
             */
            FriendAcceptNotify.prototype.userName = "";

            /**
             * FriendAcceptNotify nickname.
             * @member {string} nickname
             * @memberof im.relation.FriendAcceptNotify
             * @instance
             */
            FriendAcceptNotify.prototype.nickname = "";

            /**
             * FriendAcceptNotify avatar.
             * @member {string} avatar
             * @memberof im.relation.FriendAcceptNotify
             * @instance
             */
            FriendAcceptNotify.prototype.avatar = "";

            /**
             * Creates a new FriendAcceptNotify instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {im.relation.IFriendAcceptNotify=} [properties] Properties to set
             * @returns {im.relation.FriendAcceptNotify} FriendAcceptNotify instance
             */
            FriendAcceptNotify.create = function create(properties) {
                return new FriendAcceptNotify(properties);
            };

            /**
             * Encodes the specified FriendAcceptNotify message. Does not implicitly {@link im.relation.FriendAcceptNotify.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {im.relation.IFriendAcceptNotify} message FriendAcceptNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAcceptNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.userName);
                if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                    writer.uint32(/* id 3, wireType 2 =*/26).string(message.nickname);
                if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                    writer.uint32(/* id 4, wireType 2 =*/34).string(message.avatar);
                return writer;
            };

            /**
             * Encodes the specified FriendAcceptNotify message, length delimited. Does not implicitly {@link im.relation.FriendAcceptNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {im.relation.IFriendAcceptNotify} message FriendAcceptNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendAcceptNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendAcceptNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendAcceptNotify} FriendAcceptNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAcceptNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendAcceptNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.userId = reader.string();
                            break;
                        }
                    case 2: {
                            message.userName = reader.string();
                            break;
                        }
                    case 3: {
                            message.nickname = reader.string();
                            break;
                        }
                    case 4: {
                            message.avatar = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendAcceptNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendAcceptNotify} FriendAcceptNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendAcceptNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendAcceptNotify message.
             * @function verify
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendAcceptNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                    if (!$util.isString(message.userName))
                        return "userName: string expected";
                if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                    if (!$util.isString(message.nickname))
                        return "nickname: string expected";
                if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                    if (!$util.isString(message.avatar))
                        return "avatar: string expected";
                return null;
            };

            /**
             * Creates a FriendAcceptNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendAcceptNotify} FriendAcceptNotify
             */
            FriendAcceptNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendAcceptNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendAcceptNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendAcceptNotify();
                if (object.userId != null)
                    message.userId = String(object.userId);
                if (object.userName != null)
                    message.userName = String(object.userName);
                if (object.nickname != null)
                    message.nickname = String(object.nickname);
                if (object.avatar != null)
                    message.avatar = String(object.avatar);
                return message;
            };

            /**
             * Creates a plain object from a FriendAcceptNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {im.relation.FriendAcceptNotify} message FriendAcceptNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendAcceptNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.userId = "";
                    object.userName = "";
                    object.nickname = "";
                    object.avatar = "";
                }
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                if (message.userName != null && Object.hasOwnProperty.call(message, "userName"))
                    object.userName = message.userName;
                if (message.nickname != null && Object.hasOwnProperty.call(message, "nickname"))
                    object.nickname = message.nickname;
                if (message.avatar != null && Object.hasOwnProperty.call(message, "avatar"))
                    object.avatar = message.avatar;
                return object;
            };

            /**
             * Converts this FriendAcceptNotify to JSON.
             * @function toJSON
             * @memberof im.relation.FriendAcceptNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendAcceptNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendAcceptNotify
             * @function getTypeUrl
             * @memberof im.relation.FriendAcceptNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendAcceptNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendAcceptNotify";
            };

            return FriendAcceptNotify;
        })();

        relation.FriendDeleteReq = (function() {

            /**
             * Properties of a FriendDeleteReq.
             * @memberof im.relation
             * @interface IFriendDeleteReq
             * @property {string|null} [userId] FriendDeleteReq userId
             * @property {string|null} [friendId] FriendDeleteReq friendId
             */

            /**
             * Constructs a new FriendDeleteReq.
             * @memberof im.relation
             * @classdesc Represents a FriendDeleteReq.
             * @implements IFriendDeleteReq
             * @constructor
             * @param {im.relation.IFriendDeleteReq=} [properties] Properties to set
             */
            function FriendDeleteReq(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendDeleteReq userId.
             * @member {string} userId
             * @memberof im.relation.FriendDeleteReq
             * @instance
             */
            FriendDeleteReq.prototype.userId = "";

            /**
             * FriendDeleteReq friendId.
             * @member {string} friendId
             * @memberof im.relation.FriendDeleteReq
             * @instance
             */
            FriendDeleteReq.prototype.friendId = "";

            /**
             * Creates a new FriendDeleteReq instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {im.relation.IFriendDeleteReq=} [properties] Properties to set
             * @returns {im.relation.FriendDeleteReq} FriendDeleteReq instance
             */
            FriendDeleteReq.create = function create(properties) {
                return new FriendDeleteReq(properties);
            };

            /**
             * Encodes the specified FriendDeleteReq message. Does not implicitly {@link im.relation.FriendDeleteReq.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {im.relation.IFriendDeleteReq} message FriendDeleteReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendDeleteReq.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.friendId);
                return writer;
            };

            /**
             * Encodes the specified FriendDeleteReq message, length delimited. Does not implicitly {@link im.relation.FriendDeleteReq.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {im.relation.IFriendDeleteReq} message FriendDeleteReq message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendDeleteReq.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendDeleteReq message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendDeleteReq} FriendDeleteReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendDeleteReq.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendDeleteReq();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.userId = reader.string();
                            break;
                        }
                    case 2: {
                            message.friendId = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendDeleteReq message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendDeleteReq} FriendDeleteReq
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendDeleteReq.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendDeleteReq message.
             * @function verify
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendDeleteReq.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    if (!$util.isString(message.friendId))
                        return "friendId: string expected";
                return null;
            };

            /**
             * Creates a FriendDeleteReq message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendDeleteReq} FriendDeleteReq
             */
            FriendDeleteReq.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendDeleteReq)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendDeleteReq: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendDeleteReq();
                if (object.userId != null)
                    message.userId = String(object.userId);
                if (object.friendId != null)
                    message.friendId = String(object.friendId);
                return message;
            };

            /**
             * Creates a plain object from a FriendDeleteReq message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {im.relation.FriendDeleteReq} message FriendDeleteReq
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendDeleteReq.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.userId = "";
                    object.friendId = "";
                }
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                if (message.friendId != null && Object.hasOwnProperty.call(message, "friendId"))
                    object.friendId = message.friendId;
                return object;
            };

            /**
             * Converts this FriendDeleteReq to JSON.
             * @function toJSON
             * @memberof im.relation.FriendDeleteReq
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendDeleteReq.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendDeleteReq
             * @function getTypeUrl
             * @memberof im.relation.FriendDeleteReq
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendDeleteReq.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendDeleteReq";
            };

            return FriendDeleteReq;
        })();

        relation.FriendDeleteResp = (function() {

            /**
             * Properties of a FriendDeleteResp.
             * @memberof im.relation
             * @interface IFriendDeleteResp
             * @property {number|null} [code] FriendDeleteResp code
             * @property {string|null} [message] FriendDeleteResp message
             */

            /**
             * Constructs a new FriendDeleteResp.
             * @memberof im.relation
             * @classdesc Represents a FriendDeleteResp.
             * @implements IFriendDeleteResp
             * @constructor
             * @param {im.relation.IFriendDeleteResp=} [properties] Properties to set
             */
            function FriendDeleteResp(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendDeleteResp code.
             * @member {number} code
             * @memberof im.relation.FriendDeleteResp
             * @instance
             */
            FriendDeleteResp.prototype.code = 0;

            /**
             * FriendDeleteResp message.
             * @member {string} message
             * @memberof im.relation.FriendDeleteResp
             * @instance
             */
            FriendDeleteResp.prototype.message = "";

            /**
             * Creates a new FriendDeleteResp instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {im.relation.IFriendDeleteResp=} [properties] Properties to set
             * @returns {im.relation.FriendDeleteResp} FriendDeleteResp instance
             */
            FriendDeleteResp.create = function create(properties) {
                return new FriendDeleteResp(properties);
            };

            /**
             * Encodes the specified FriendDeleteResp message. Does not implicitly {@link im.relation.FriendDeleteResp.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {im.relation.IFriendDeleteResp} message FriendDeleteResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendDeleteResp.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.code);
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.message);
                return writer;
            };

            /**
             * Encodes the specified FriendDeleteResp message, length delimited. Does not implicitly {@link im.relation.FriendDeleteResp.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {im.relation.IFriendDeleteResp} message FriendDeleteResp message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendDeleteResp.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendDeleteResp message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendDeleteResp} FriendDeleteResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendDeleteResp.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendDeleteResp();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.code = reader.int32();
                            break;
                        }
                    case 2: {
                            message.message = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendDeleteResp message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendDeleteResp} FriendDeleteResp
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendDeleteResp.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendDeleteResp message.
             * @function verify
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendDeleteResp.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    if (!$util.isInteger(message.code))
                        return "code: integer expected";
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    if (!$util.isString(message.message))
                        return "message: string expected";
                return null;
            };

            /**
             * Creates a FriendDeleteResp message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendDeleteResp} FriendDeleteResp
             */
            FriendDeleteResp.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendDeleteResp)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendDeleteResp: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendDeleteResp();
                if (object.code != null)
                    message.code = object.code | 0;
                if (object.message != null)
                    message.message = String(object.message);
                return message;
            };

            /**
             * Creates a plain object from a FriendDeleteResp message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {im.relation.FriendDeleteResp} message FriendDeleteResp
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendDeleteResp.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.code = 0;
                    object.message = "";
                }
                if (message.code != null && Object.hasOwnProperty.call(message, "code"))
                    object.code = message.code;
                if (message.message != null && Object.hasOwnProperty.call(message, "message"))
                    object.message = message.message;
                return object;
            };

            /**
             * Converts this FriendDeleteResp to JSON.
             * @function toJSON
             * @memberof im.relation.FriendDeleteResp
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendDeleteResp.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendDeleteResp
             * @function getTypeUrl
             * @memberof im.relation.FriendDeleteResp
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendDeleteResp.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendDeleteResp";
            };

            return FriendDeleteResp;
        })();

        relation.FriendDeleteNotify = (function() {

            /**
             * Properties of a FriendDeleteNotify.
             * @memberof im.relation
             * @interface IFriendDeleteNotify
             * @property {string|null} [userId] FriendDeleteNotify userId
             */

            /**
             * Constructs a new FriendDeleteNotify.
             * @memberof im.relation
             * @classdesc Represents a FriendDeleteNotify.
             * @implements IFriendDeleteNotify
             * @constructor
             * @param {im.relation.IFriendDeleteNotify=} [properties] Properties to set
             */
            function FriendDeleteNotify(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * FriendDeleteNotify userId.
             * @member {string} userId
             * @memberof im.relation.FriendDeleteNotify
             * @instance
             */
            FriendDeleteNotify.prototype.userId = "";

            /**
             * Creates a new FriendDeleteNotify instance using the specified properties.
             * @function create
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {im.relation.IFriendDeleteNotify=} [properties] Properties to set
             * @returns {im.relation.FriendDeleteNotify} FriendDeleteNotify instance
             */
            FriendDeleteNotify.create = function create(properties) {
                return new FriendDeleteNotify(properties);
            };

            /**
             * Encodes the specified FriendDeleteNotify message. Does not implicitly {@link im.relation.FriendDeleteNotify.verify|verify} messages.
             * @function encode
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {im.relation.IFriendDeleteNotify} message FriendDeleteNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendDeleteNotify.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.userId);
                return writer;
            };

            /**
             * Encodes the specified FriendDeleteNotify message, length delimited. Does not implicitly {@link im.relation.FriendDeleteNotify.verify|verify} messages.
             * @function encodeDelimited
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {im.relation.IFriendDeleteNotify} message FriendDeleteNotify message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            FriendDeleteNotify.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a FriendDeleteNotify message from the specified reader or buffer.
             * @function decode
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {im.relation.FriendDeleteNotify} FriendDeleteNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendDeleteNotify.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end = length === undefined ? reader.len : reader.pos + length, message = new $root.im.relation.FriendDeleteNotify();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.userId = reader.string();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                return message;
            };

            /**
             * Decodes a FriendDeleteNotify message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {im.relation.FriendDeleteNotify} FriendDeleteNotify
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            FriendDeleteNotify.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a FriendDeleteNotify message.
             * @function verify
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            FriendDeleteNotify.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    if (!$util.isString(message.userId))
                        return "userId: string expected";
                return null;
            };

            /**
             * Creates a FriendDeleteNotify message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {im.relation.FriendDeleteNotify} FriendDeleteNotify
             */
            FriendDeleteNotify.fromObject = function fromObject(object, long) {
                if (object instanceof $root.im.relation.FriendDeleteNotify)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".im.relation.FriendDeleteNotify: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.im.relation.FriendDeleteNotify();
                if (object.userId != null)
                    message.userId = String(object.userId);
                return message;
            };

            /**
             * Creates a plain object from a FriendDeleteNotify message. Also converts values to other types if specified.
             * @function toObject
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {im.relation.FriendDeleteNotify} message FriendDeleteNotify
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            FriendDeleteNotify.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults)
                    object.userId = "";
                if (message.userId != null && Object.hasOwnProperty.call(message, "userId"))
                    object.userId = message.userId;
                return object;
            };

            /**
             * Converts this FriendDeleteNotify to JSON.
             * @function toJSON
             * @memberof im.relation.FriendDeleteNotify
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            FriendDeleteNotify.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for FriendDeleteNotify
             * @function getTypeUrl
             * @memberof im.relation.FriendDeleteNotify
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            FriendDeleteNotify.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/im.relation.FriendDeleteNotify";
            };

            return FriendDeleteNotify;
        })();

        return relation;
    })();

    return im;
})();

export { $root as default };
