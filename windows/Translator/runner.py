"""Line-delimited JSON over stdio. No server, downloads, shell, or user-controlled imports."""
import json
import os
from pathlib import Path
import re
import sys

sys.stdin.reconfigure(encoding="utf-8")
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
os.environ.setdefault("OMP_NUM_THREADS", "4")
import ctranslate2
import sentencepiece

folder = Path(sys.argv[1]).resolve()
translator = ctranslate2.Translator(str(folder / "model"), device="cpu", compute_type="int8", intra_threads=4, inter_threads=1)
source = sentencepiece.SentencePieceProcessor(model_proto=(folder / "source.spm").read_bytes())
target = sentencepiece.SentencePieceProcessor(model_proto=(folder / "target.spm").read_bytes())
for line in sys.stdin:
    try:
        text = json.loads(line)["text"]
        if not isinstance(text, str) or not text.strip() or len(text) > 3000:
            raise ValueError("Выделите от 1 до 3000 символов")
        # Keep paragraphs; bounded chunks avoid silent truncation of very long sentences.
        blocks = re.split(r"(\n+)", text)
        out = []
        for block in blocks:
            if not block.strip():
                out.append(block)
                continue
            sentences = re.split(r"(?<=[.!?])\s+", block.strip())
            for sentence in sentences:
                tokens = source.encode(sentence, out_type=str)
                if len(tokens) > 450:
                    raise ValueError("Слишком длинное предложение. Выделите меньший фрагмент.")
                result = translator.translate_batch([tokens], beam_size=4, max_decoding_length=512)[0]
                out.append(target.decode(result.hypotheses[0]))
                out.append(" ")
        print(json.dumps({"translation": "".join(out).strip()}, ensure_ascii=False), flush=True)
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), flush=True)
