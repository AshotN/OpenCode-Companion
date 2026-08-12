const IDE_GUIDANCE = __OPENCODE_RELAY_IDE_GUIDANCE__

export const OpenCodeRelayPromptPlugin = async () => ({
    "experimental.chat.system.transform": async (_input, output) => {
        if (!Array.isArray(output.system)) return
        if (!output.system.includes(IDE_GUIDANCE)) output.system.unshift(IDE_GUIDANCE)
    },
})

export default OpenCodeRelayPromptPlugin
