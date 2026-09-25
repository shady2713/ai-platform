export { createHostBridge, HostBridge } from './bridge/host-bridge';
export type {
  HostBridgeEvent,
  HostBridgeOptions,
  HostBridgeTransport,
} from './bridge/host-bridge';
export { AiChatApiError, createAiChatClient } from './client';
export type {
  AiChatClient,
  AiChatClientOptions,
  RunEventHandlers,
  RunEventStreamResult,
} from './client';
export { createChatMount } from './display/mount';
export type {
  ChatDisplayMode,
  ChatFramePort,
  ChatMount,
  ChatMountLayout,
  ChatMountOptions,
} from './display/mount';
export type {
  AiRunStatus,
  BridgeState,
  CreateRunRequest,
  RunAccepted,
  RunSnapshot,
} from './types';
export {
  BRIDGE_MESSAGE_TYPES,
  BRIDGE_PROTOCOL_VERSION,
  isCompatibleProtocolVersion,
  parseBridgeMessage,
  whitelistedBridgeType,
} from '@vben/ai-contracts';
export type { Theme } from '@vben/ai-contracts';
export type {
  BridgeAuth,
  BridgeDestroy,
  BridgeError,
  BridgeHello,
  BridgeInit,
  BridgeMessage,
  BridgeMessageType,
  BridgeReady,
  BridgeTokenRequired,
} from '@vben/ai-contracts';
