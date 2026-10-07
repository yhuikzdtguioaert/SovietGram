"""Parse changed music/integration Java without invoking Android or Gradle.
Requires tree-sitter==0.26.0 and tree-sitter-java==0.23.5.
This checks syntax, not Android symbols or runtime behavior.
"""
from pathlib import Path
import sys
from tree_sitter import Language, Parser
import tree_sitter_java

root = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram'
parser = Parser(Language(tree_sitter_java.language()))
paths = [
    root / 'helpers/ProfileMusicHelper.java',
    root / 'helpers/CustomProfileIntegrationOAuth.java',
    root / 'helpers/CustomProfileIntegrations.java',
    root / 'settings/CustomProfileBlocksActivity.java',
]
failed = False
for path in paths:
    tree = parser.parse(path.read_bytes())
    if tree.root_node.has_error:
        failed = True
        stack = [tree.root_node]
        while stack:
            node = stack.pop()
            if node.type == 'ERROR' or node.is_missing:
                print(f'{path.name}:{node.start_point.row + 1}:{node.start_point.column + 1}: {node.type}')
            stack.extend(reversed(node.children))
    else:
        print(f'{path.name}: syntax OK')
sys.exit(1 if failed else 0)
