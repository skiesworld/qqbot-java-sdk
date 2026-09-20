"""One-shot: insert the curated eventRoles block into naming.json. Delete after running."""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(HERE, 'naming.json')
MARKER = '\n  "typeAliases": {'

ROWS = [
    ('READY', 'PLAIN', [], [], 'the session opening; nothing happened somewhere'),
    ('RESUMED', 'PLAIN', [], [], 'the session resumed; nothing happened somewhere'),

    ('C2C_MESSAGE_CREATE', 'MESSAGE', [], [], None),
    ('GROUP_AT_MESSAGE_CREATE', 'MESSAGE', [], [], None),
    ('GROUP_MESSAGE_CREATE', 'MESSAGE', [], [], None),
    ('AT_MESSAGE_CREATE', 'MESSAGE', [], [], None),
    ('MESSAGE_CREATE', 'MESSAGE', [], [], None),
    ('DIRECT_MESSAGE_CREATE', 'MESSAGE', [], [], None),

    ('GROUP_JOIN_REQUEST', 'REQUEST', ['member_openid'], [],
     'the applicant waits on approve()/deny()'),
    ('INTERACTION_CREATE', 'REQUEST', ['user_openid', 'group_member_openid'], [],
     'the button or shortcut stays pending until acknowledgeInteraction answers it'),

    ('FRIEND_ADD', 'NOTICE', ['openid'], [], None),
    ('FRIEND_DEL', 'NOTICE', ['openid'], [], None),
    ('C2C_MSG_REJECT', 'NOTICE', ['openid'], [], None),
    ('C2C_MSG_RECEIVE', 'NOTICE', ['openid'], [], None),
    ('GROUP_ADD_ROBOT', 'NOTICE', ['op_member_openid'], [],
     'the member who pulled the bot in; nobody is acted upon'),
    ('GROUP_DEL_ROBOT', 'NOTICE', ['op_member_openid'], [], None),
    ('GROUP_MSG_REJECT', 'NOTICE', ['op_member_openid'], [], None),
    ('GROUP_MSG_RECEIVE', 'NOTICE', ['op_member_openid'], [], None),
    ('SUBSCRIBE_MESSAGE_STATUS', 'NOTICE', ['openid'], [], None),
    ('GROUP_MEMBER_ADD', 'NOTICE', [], ['member_openid'],
     'member_openid is the member; the docs give no evidence that user_openid is an operator'),
    ('GROUP_MEMBER_REMOVE', 'NOTICE', [], ['member_openid'], None),

    ('GUILD_CREATE', 'NOTICE', [], [], 'owner_id/op_user_id are guild-space ids, not openids'),
    ('GUILD_UPDATE', 'NOTICE', [], [], None),
    ('GUILD_DELETE', 'NOTICE', [], [], None),
    ('CHANNEL_CREATE', 'NOTICE', [], [], None),
    ('CHANNEL_UPDATE', 'NOTICE', [], [], None),
    ('CHANNEL_DELETE', 'NOTICE', [], [], None),
    ('GUILD_MEMBER_ADD', 'NOTICE', [], [], 'the member object reports a nick, not an id'),
    ('GUILD_MEMBER_UPDATE', 'NOTICE', [], [], None),
    ('GUILD_MEMBER_REMOVE', 'NOTICE', [], [], None),
    ('MESSAGE_DELETE', 'NOTICE', [], [], 'no text left to read, so not a message envelope'),
    ('PUBLIC_MESSAGE_DELETE', 'NOTICE', [], [], None),
    ('DIRECT_MESSAGE_DELETE', 'NOTICE', [], [], None),
    ('MESSAGE_REACTION_ADD', 'NOTICE', [], [], 'user_id is a guild-space id, not an openid'),
    ('MESSAGE_REACTION_REMOVE', 'NOTICE', [], [], None),
    ('FORUM_THREAD_CREATE', 'NOTICE', [], [], None),
    ('FORUM_THREAD_UPDATE', 'NOTICE', [], [], None),
    ('FORUM_THREAD_DELETE', 'NOTICE', [], [], None),
    ('FORUM_POST_CREATE', 'NOTICE', [], [], None),
    ('FORUM_POST_DELETE', 'NOTICE', [], [], None),
    ('FORUM_REPLY_CREATE', 'NOTICE', [], [], None),
    ('FORUM_REPLY_DELETE', 'NOTICE', [], [], None),
    ('FORUM_PUBLISH_AUDIT_RESULT', 'NOTICE', [], [],
     'a verdict about a publish, but nothing is owed back for it'),
    ('AUDIO_START', 'NOTICE', [], [], 'a text-to-speech job started; no answer is owed'),
    ('AUDIO_FINISH', 'NOTICE', [], [], None),
    ('AUDIO_ON_MIC', 'NOTICE', [], [], 'someone spoke up in the channel'),
    ('AUDIO_OFF_MIC', 'NOTICE', [], [], None),
]

BLOCK = ['\n  "eventRoles": {',
         '\n    "_comment": "One row per event the gateway can push. envelope is what the bus builds and what '
         'a handler parameter type therefore means; actor/subject name the payload keys that report who set the '
         'event off and who it happened to, empty when the docs report no person in the openid space for that '
         'role. A crawled event with no row fails the build; EventRoutingTest holds this list against EventType."',]
for name, envelope, actor, subject, doc in ROWS:
    entry = {'envelope': envelope}
    if actor:
        entry['actor'] = actor
    if subject:
        entry['subject'] = subject
    if doc:
        entry['doc'] = doc
    BLOCK.append('\n    %s: %s,' % (json.dumps(name), json.dumps(entry, ensure_ascii=False)))
BLOCK[-1] = BLOCK[-1].rstrip(',')
BLOCK.append('\n  },')

with io.open(PATH, encoding='utf-8', newline='') as handle:
    text = handle.read()
if '"eventRoles"' in text:
    raise SystemExit('naming.json already has eventRoles')
index = text.index(MARKER)
patched = text[:index] + ''.join(BLOCK) + text[index:]
json.loads(patched)
with io.open(PATH, 'w', encoding='utf-8', newline='\n') as handle:
    handle.write(patched)
print('eventRoles rows: %d' % len(ROWS))
