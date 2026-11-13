/**
 * Hangul → the keys a person presses for it on the Korean 2-set (두벌식) keyboard, as QWERTY
 * letters. `type -- dkssud` with the simulator keyboard in Korean mode composes 안녕: the
 * simulator's own IME joins the jamo into syllables, the same way it does for someone typing, so
 * text that arrives a syllable at a time can be sent a syllable at a time.
 *
 * Shifted keys are the uppercase letters (ㄲ R, ㅆ T, ㅒ O …). Compound vowels and final clusters are
 * typed as their two parts (ㅘ hk, ㄺ fr), which is how the 2-set layout enters them.
 */

/** Initial consonants, in Unicode order (ㄱ ㄲ ㄴ ㄷ ㄸ ㄹ ㅁ ㅂ ㅃ ㅅ ㅆ ㅇ ㅈ ㅉ ㅊ ㅋ ㅌ ㅍ ㅎ). */
const INITIAL = ['r', 'R', 's', 'e', 'E', 'f', 'a', 'q', 'Q', 't', 'T', 'd', 'w', 'W', 'c', 'z', 'x', 'v', 'g'];

/** Vowels, in Unicode order (ㅏ ㅐ ㅑ ㅒ ㅓ ㅔ ㅕ ㅖ ㅗ ㅘ ㅙ ㅚ ㅛ ㅜ ㅝ ㅞ ㅟ ㅠ ㅡ ㅢ ㅣ). */
const MEDIAL = ['k', 'o', 'i', 'O', 'j', 'p', 'u', 'P', 'h', 'hk', 'ho', 'hl', 'y', 'n', 'nj', 'np', 'nl', 'b', 'm', 'ml', 'l'];

/** Final consonants, in Unicode order, none first (ㄱ ㄲ ㄳ ㄴ ㄵ ㄶ ㄷ ㄹ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅁ ㅂ ㅄ ㅅ ㅆ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ). */
const FINAL = [
  '', 'r', 'R', 'rt', 's', 'sw', 'sg', 'e', 'f', 'fr', 'fa', 'fq', 'ft', 'fx', 'fv', 'fg',
  'a', 'q', 'qt', 't', 'T', 'd', 'w', 'c', 'z', 'x', 'v', 'g',
];

/** Compatibility jamo U+3131…U+314E, the consonants typed alone (ㄱ ㄲ ㄳ ㄴ … ㅎ). */
const JAMO_CONSONANTS = [
  'r', 'R', 'rt', 's', 'sw', 'sg', 'e', 'E', 'f', 'fr', 'fa', 'fq', 'ft', 'fx', 'fv', 'fg',
  'a', 'q', 'Q', 'qt', 't', 'T', 'd', 'w', 'W', 'c', 'z', 'x', 'v', 'g',
];

const SYLLABLE_FIRST = 0xac00; // 가
const SYLLABLE_LAST = 0xd7a3; // 힣
const JAMO_FIRST = 0x3131; // ㄱ
const VOWEL_FIRST = 0x314f; // ㅏ
const JAMO_LAST = 0x3163; // ㅣ; the archaic jamo after it have no key

/**
 * The 2-set keys for one character, or null when it is not Hangul a keyboard can type.
 *
 * @param {string} ch one character
 * @returns {string|null}
 */
export function hangulKeys(ch) {
  const code = ch.codePointAt(0);
  if (code >= SYLLABLE_FIRST && code <= SYLLABLE_LAST) {
    const n = code - SYLLABLE_FIRST;
    return INITIAL[Math.floor(n / 588)] + MEDIAL[Math.floor((n % 588) / 28)] + FINAL[n % 28];
  }
  if (code >= JAMO_FIRST && code < VOWEL_FIRST) return JAMO_CONSONANTS[code - JAMO_FIRST];
  if (code >= VOWEL_FIRST && code <= JAMO_LAST) return MEDIAL[code - VOWEL_FIRST];
  return null;
}
