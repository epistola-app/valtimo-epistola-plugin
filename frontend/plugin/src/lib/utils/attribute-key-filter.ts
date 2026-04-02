/**
 * Filter available attribute keys for combobox suggestions.
 * Excludes keys already used by other entries and filters by current input.
 */
export function filterAttributeKeys(
  availableKeys: string[],
  usedKeys: string[],
  currentInput: string
): string[] {
  const usedSet = new Set(usedKeys);
  return availableKeys.filter(key =>
    !usedSet.has(key) && (!currentInput || key.toLowerCase().includes(currentInput.toLowerCase()))
  );
}
