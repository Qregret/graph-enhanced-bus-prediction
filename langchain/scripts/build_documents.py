from __future__ import annotations

import json
import sys
from dataclasses import asdict
from pathlib import Path


SERVICE_DIR = Path(__file__).resolve().parents[1]
if str(SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(SERVICE_DIR))

from retrieval.loader import build_documents, save_documents


def main() -> None:
    knowledge, evaluation, stats = build_documents()
    save_documents(knowledge, evaluation, stats)
    print(json.dumps(asdict(stats), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
