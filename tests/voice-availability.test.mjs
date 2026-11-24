import test from 'node:test';
import assert from 'node:assert/strict';
import {DEEPSEEK_TEXT_ONLY_REASON,voiceAvailability,voiceInputAvailability} from '../server/voice-availability.mjs';

test('DeepSeek environments disable native voice while keeping text coordinator input available',()=>{
  assert.deepEqual(voiceInputAvailability({providerId:'deepseek'}),{available:false,providerId:'deepseek',reason:DEEPSEEK_TEXT_ONLY_REASON});
  assert.deepEqual(voiceInputAvailability({providerId:'openai'}),{available:true,providerId:'openai',reason:null});
  assert.deepEqual(voiceInputAvailability(null),{available:true,providerId:null,reason:null});
});

test('DeepSeek disables manual speech output as well as capture, while older clients retain the input projection',()=>{
  assert.deepEqual(voiceAvailability({providerId:'deepseek'}),{available:false,input:false,output:false,providerId:'deepseek',reason:DEEPSEEK_TEXT_ONLY_REASON});
  assert.deepEqual(voiceAvailability({providerId:'openai'}),{available:true,input:true,output:true,providerId:'openai',reason:null});
  assert.deepEqual(voiceAvailability(null),{available:true,input:true,output:true,providerId:null,reason:null});
});
