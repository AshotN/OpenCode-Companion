const IDE_GUIDANCE = __OPENCODE_RELAY_IDE_GUIDANCE__

export const OpenCodeRelayPromptPlugin = async () => ({
    "experimental.chat.system.transform": async (_input, output) => {
        if (!Array.isArray(output.system)) return
        if (output.system.some((message) => typeof message === "string" && message.includes(IDE_GUIDANCE))) return

        output.system[0] = output.system[0]
            ? `${output.system[0]}\n\n${IDE_GUIDANCE}`
            : IDE_GUIDANCE
    },
})

export default OpenCodeRelayPromptPlugin
