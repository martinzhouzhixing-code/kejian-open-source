package app.kejian.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class NoteDocumentBlock(val text:String,val kind:String="paragraph",val level:Int=0)
data class NoteKnowledgeSection(val title:String,val details:String)

/** Format only: preserve original words, numbers, formulae, indentation and ordering. */
internal fun noteDocumentBlocks(text:String):List<NoteDocumentBlock>{
    val blocks=mutableListOf<NoteDocumentBlock>();val paragraph=mutableListOf<String>()
    fun flush(){if(paragraph.isNotEmpty()){paragraph.joinToString("\n").chunked(6000).forEach {blocks+=NoteDocumentBlock(it)};paragraph.clear()}}
    val heading=Regex("^#{1,6}\\s+(.+)$")
    val bullet=Regex("^(\\s*)([-*•◦○]|[0-9]+[.)、])\\s+(.+)$")
    val titles=setOf("课程概览","课程概要","概览","摘要","课堂问题","问题","讨论问题","老师提醒","待确认","Overview","Summary","Questions","Class questions","Reminders","Uncertainties")
    text.lines().forEach {line->
        val trimmed=line.trim();val h=heading.matchEntire(trimmed);val b=bullet.matchEntire(line)
        when {
            trimmed.startsWith("[W")&&safeNoteUrl(trimmed.substringAfter(" "))->{flush();blocks+=NoteDocumentBlock(trimmed)}
            trimmed.isBlank()->flush()
            h!=null->{flush();blocks+=NoteDocumentBlock(h.groupValues[1],"heading")}
            trimmed.removeSuffix(":").removeSuffix("：") in titles->{flush();blocks+=NoteDocumentBlock(trimmed.removeSuffix(":").removeSuffix("："),"heading")}
            b!=null->{flush();val indent=b.groupValues[1].replace("\t","  ").length/2;blocks+=NoteDocumentBlock(b.groupValues[3],"bullet",indent.coerceIn(0,3))}
            trimmed.startsWith("> ")->{flush();blocks+=NoteDocumentBlock(trimmed.removePrefix("> "),"quote")}
            else->paragraph+=line
        }
    };flush();return blocks
}
internal fun noteKnowledgeSection(text:String,index:Int,english:Boolean):NoteKnowledgeSection{
    val lines=text.trim().lines()
    // A decimal point followed by a digit is part of the lesson content, not
    // an ordered-list marker. Keep compact list forms such as 2.Topic / 3)题目.
    val first=lines.firstOrNull().orEmpty().trim().replace(Regex("^#{1,6}\\s+"),"").replace(Regex("^[0-9]+(?:\\.(?![0-9])|[)、])\\s*"),"").removeSurrounding("**")
    if(lines.size>1&&first.length<=160)return NoteKnowledgeSection(first,lines.drop(1).joinToString("\n"))
    val split=Regex("^(.{2,60}?)[：:]\\s*(.+)$").matchEntire(first)
    if(split!=null)return NoteKnowledgeSection(split.groupValues[1],split.groupValues[2])
    return if(first.length<=160)NoteKnowledgeSection(first,"")else NoteKnowledgeSection(if(english)"Knowledge point ${index+1}"else"知识点 ${index+1}",text)
}
internal fun noteInlineText(text:String):AnnotatedString=buildAnnotatedString {
    var offset=0
    Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach {match->
        append(text.substring(offset,match.range.first));val start=length;append(match.groupValues[1])
        addStyle(SpanStyle(fontWeight=FontWeight.SemiBold),start,length);offset=match.range.last+1
    };append(text.substring(offset))
}

@Composable internal fun NoteDocumentHeading(text:String,tag:String?=null){
    Text(text,modifier=Modifier.fillMaxWidth().padding(top=12.dp,bottom=2.dp).then(if(tag==null)Modifier else Modifier.testTag(tag)),fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold)
}
@Composable internal fun NoteDocumentText(text:String){
    val blocks=remember(text){noteDocumentBlocks(text)}
    SelectionContainer {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(11.dp)){
            blocks.forEach {block->when {
                block.text.startsWith("[figure:")->NoteMathFigure(block.text.substringAfter("[figure:").substringBefore("]"))
                safeNoteUrl(block.text.substringAfter(" ",block.text))->NoteSourceLink(block.text.substringAfter(" ",block.text))
                else->when(block.kind){
                "heading"->Text(noteInlineText(block.text),Modifier.padding(top=10.dp),fontSize=21.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold)
                "bullet"->Row(Modifier.fillMaxWidth().padding(start=(block.level*17+3).dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){
                    Text(if(block.level==0)"•"else"◦",fontSize=15.sp,lineHeight=26.sp,color=if(block.level==0)MaterialTheme.colorScheme.onSurface else Muted)
                    Text(noteInlineText(block.text),Modifier.weight(1f),fontSize=15.sp,lineHeight=26.sp)
                }
                "quote"->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("│",color=Brand,fontSize=20.sp);Text(noteInlineText(block.text),Modifier.weight(1f),fontSize=15.sp,lineHeight=26.sp,color=Muted)
                }
                else->Text(noteInlineText(block.text),fontSize=15.sp,lineHeight=27.sp)
            }}}
        }
    }
}
@Composable internal fun NoteKnowledgePoint(index:Int,text:String){
    val english=AppLanguage.english
    val section=remember(text,index,english){noteKnowledgeSection(text,index,english)}
    Column(Modifier.fillMaxWidth().testTag("note_knowledge_$index"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("${index+1}. ${section.title}",fontSize=18.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold)
        if(section.details.isNotBlank())Box(Modifier.padding(start=8.dp)){NoteDocumentText(section.details)}
    }
}
@Composable internal fun NoteAssignment(index:Int,text:String,checked:Boolean,enabled:Boolean,onCheck:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().testTag("note_assignment_$index"),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Checkbox(checked=checked,onCheckedChange=onCheck,enabled=enabled,modifier=Modifier.size(30.dp).padding(top=3.dp))
        Text(noteInlineText("${index+1}. "+text.removePrefix("- [ ] ").removePrefix("- [x] ")),Modifier.weight(1f),fontSize=15.sp,lineHeight=26.sp,
            color=if(checked)Muted else MaterialTheme.colorScheme.onSurface,textDecoration=if(checked)TextDecoration.LineThrough else TextDecoration.None)
    }
}
