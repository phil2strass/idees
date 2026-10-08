"""Real MCP stdio handshake and tool calls against the isolated integration API."""
import asyncio
import os
from pathlib import Path
import sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def main():
    if os.environ.get('IDEE_ALLOW_TEST_WRITES')!='isolated':
        raise RuntimeError('Set IDEE_ALLOW_TEST_WRITES=isolated for this test.')
    params=StdioServerParameters(command=sys.executable,args=[str(Path('idee-mcp/server.py').resolve())],env=dict(os.environ))
    async with stdio_client(params) as (read,write):
        async with ClientSession(read,write) as client:
            await client.initialize()
            listing=await client.list_tools()
            assert {t.name for t in listing.tools}=={'list_categories','list_outings','create_outing','add_outing_images','add_primary_image_by_url'}
            result=await client.call_tool('list_categories',{})
            assert not result.isError,result
            result=await client.call_tool('list_outings',{'date':'2027-02-06'})
            assert not result.isError,result
            sys.path.insert(0,str(Path('tests').resolve()))
            from test_api import outing
            payload=outing('mcp-created')
            payload['images']=[dict(url='https://example.org/mcp-cover.jpg',alt='Affiche de test',credit='Test',primary=True)]
            result=await client.call_tool('create_outing',{'outing':payload})
            assert not result.isError,result
            retry=await client.call_tool('create_outing',{'outing':payload})
            assert not retry.isError,retry
            added=await client.call_tool('add_outing_images',{'slug':'mcp-created','images':[{'url':'https://example.org/mcp-gallery.jpg','alt':'Galerie de test','primary':False}]})
            assert not added.isError,added
            added_retry=await client.call_tool('add_outing_images',{'slug':'mcp-created','images':[{'url':'https://example.org/mcp-gallery.jpg','alt':'Galerie de test','primary':False}]})
            assert not added_retry.isError,added_retry
            result=await client.call_tool('list_outings',{'date':'2027-02-06'})
            assert not result.isError,result
            assert 'mcp-created' in str(result),result
            print('MCP: handshake, tools/list, read by date, creation and idempotent retry passed.')

asyncio.run(main())
