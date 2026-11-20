import type { MessageDto, MessageNodeDto } from "../types/dto";

/** Presentation only: the complete node tree stays available to history and generation. */
export function isVisibleChatMessage(message: MessageDto | undefined): message is MessageDto {
  return Boolean(message && message.hideFromChat !== true);
}

export function getVisibleNodeMessages(
  nodes: readonly MessageNodeDto[],
): Array<{ node: MessageNodeDto; message: MessageDto }> {
  return nodes.flatMap((node) => {
    const message = node.messages[node.selectIndex] ?? node.messages[0];
    return isVisibleChatMessage(message) ? [{ node, message }] : [];
  });
}

/** Branch controls display a compact count but send the original variant index to the API. */
export function getVisibleBranchNavigation(node: MessageNodeDto) {
  const indices = node.messages.flatMap((message, index) =>
    isVisibleChatMessage(message) ? [index] : [],
  );
  const position = indices.indexOf(node.selectIndex);

  return {
    count: indices.length,
    position,
    previousIndex: position > 0 ? indices[position - 1] : undefined,
    nextIndex: position >= 0 ? indices[position + 1] : undefined,
  };
}
