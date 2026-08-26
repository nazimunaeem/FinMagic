import re

with open('app/src/main/java/com/finmagic/data/sms/parsers/WalletParser.kt', 'r') as f:
    content = f.read()

# Update nagadVerb to allow newline as well as pipe
content = content.replace(
    'private val nagadVerb = Regex("""^(?:\\s*\\w+\\s*:)?\\s*([A-Za-z ]{3,30}?)\\.\\s*\\|""")',
    'private val nagadVerb = Regex("""^(?:\\s*\\w+\\s*:)?\\s*([A-Za-z ]{3,30}?)\\.\\s*(?:\\||\\n)""")'
)

with open('app/src/main/java/com/finmagic/data/sms/parsers/WalletParser.kt', 'w') as f:
    f.write(content)
