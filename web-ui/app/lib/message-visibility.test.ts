import { deepEqual, equal, strictEqual } from "node:assert";
import { test } from "node:test";
import type { ConversationDto, MessageDto, MessageNodeDto } from "../types/dto";
import { convertConversationToMarkdown, convertMessageToMarkdown } from "./export-markdown";
import { getVisibleBranchNavigation, getVisibleNodeMessages } from "./message-visibility";

function message(id: string, hideFromChat?: boolean): MessageDto {
  return {
    id,
    role: "ASSISTANT",
    parts: [{ type: "text", text: id }],
    createdAt: "2026-11-13T10:00:00",
    ...(hideFromChat === undefined ? {} : { hideFromChat }),
  };
}

function node(id: string, messages: MessageDto[], selectIndex = 0): MessageNodeDto {
  return { id, messages, selectIndex };
}

test("legacy messages remain visible while hidden and empty nodes occupy no display row", () => {
  const visible = node("visible", [message("hello")]);
  const hidden = node("hidden", [message("[PASS]", true)]);

  const result = getVisibleNodeMessages([visible, hidden, node("empty", [])]);

  equal(result.length, 1);
  strictEqual(result[0].node, visible);
  strictEqual(result[0].message, visible.messages[0]);
});

test("a hidden selected branch does not silently show another variant", () => {
  const original = node("mixed", [message("visible alternative"), message("[PASS]", true)], 1);

  deepEqual(getVisibleNodeMessages([original]), []);
  equal(original.selectIndex, 1);
  equal(original.messages.length, 2);
});

test("display projection leaves the full stored reasoning and node tree untouched", () => {
  const hidden = message("[PASS]", true);
  hidden.parts.unshift({ type: "reasoning", reasoning: "A skipped reasoning block" });
  const history = [node("visible", [message("hello")]), node("hidden", [hidden])];
  const before = structuredClone(history);

  getVisibleNodeMessages(history);

  deepEqual(history, before);
  strictEqual(history[1].messages[0], hidden);
});

test("branch navigation skips hidden variants and returns their original API indices", () => {
  const variants = [message("a"), message("[PASS]", true), message("b"), message("hidden", true), message("c")];
  const current = node("mixed", variants);

  deepEqual(getVisibleBranchNavigation(current), {
    count: 3,
    position: 0,
    previousIndex: undefined,
    nextIndex: 2,
  });
  deepEqual(getVisibleBranchNavigation({ ...current, selectIndex: 2 }), {
    count: 3,
    position: 1,
    previousIndex: 0,
    nextIndex: 4,
  });
  equal(getVisibleBranchNavigation({ ...current, selectIndex: 4 }).nextIndex, undefined);
});

test("a hidden current variant has no visible navigation target", () => {
  const current = node("mixed", [message("a"), message("[PASS]", true), message("b")], 1);

  deepEqual(getVisibleBranchNavigation(current), {
    count: 2,
    position: -1,
    previousIndex: undefined,
    nextIndex: undefined,
  });
});

test("human Markdown export omits hidden replies even when reasoning is requested", () => {
  const hidden = message("[PASS]", true);
  hidden.parts.unshift({ type: "reasoning", reasoning: "A skipped reasoning block" });
  const detail: ConversationDto = {
    id: "conversation",
    assistantId: "assistant",
    title: "Visible conversation",
    messages: [node("visible", [message("hello")]), node("hidden", [hidden])],
    truncateIndex: -1,
    chatSuggestions: [],
    isPinned: false,
    createAt: 0,
    updateAt: 0,
  };

  for (const includeReasoning of [false, true]) {
    equal(
      convertConversationToMarkdown(detail, includeReasoning),
      "# Visible conversation\n\n## Assistant\n\nhello",
    );
  }
  equal(detail.messages.length, 2);
  equal(hidden.parts.length, 2);
});

test("individual hidden message export also produces no visible content", () => {
  equal(convertMessageToMarkdown(message("[PASS]", true), true), "");
});
