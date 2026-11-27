export const imageExtensions = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
} as const;
export const maxImageBytes = 10 * 1024 * 1024;

export function matchesImage(type: string, bytes: Uint8Array): boolean {
  if (type === 'image/jpeg') return bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (type === 'image/png') return [137,80,78,71,13,10,26,10].every((value,index) => bytes[index] === value);
  return type === 'image/webp' && bytes.length >= 12 &&
    String.fromCharCode(...bytes.subarray(0,4)) === 'RIFF' &&
    String.fromCharCode(...bytes.subarray(8,12)) === 'WEBP';
}
