/**
 * 中国大陆手机号粗略结构正则（唯一来源）。
 * 仅校验 11 位词法结构，不声明号段有效性；持久化统一转 E.164，
 * 语义与共享测试向量见 docs/contracts/field-catalog.yaml。
 */
const MOBILE_REGEX = /^1[3-9]\d{9}$/;

export { MOBILE_REGEX };
