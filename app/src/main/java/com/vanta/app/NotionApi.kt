package com.vanta.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object NotionApi {
    private const val VERSION="2022-06-28"
    private fun request(method:String,path:String,token:String,payload:JSONObject?=null):JSONObject {
        if(token.isBlank()) throw IOException("Save a Notion integration token in VANTA Settings first.")
        val c=URL("https://api.notion.com/v1/"+path.trimStart('/')).openConnection() as HttpURLConnection
        c.requestMethod=method; c.connectTimeout=15000; c.readTimeout=20000
        c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("Notion-Version",VERSION)
        c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","application/json")
        try {
            if(payload!=null){c.doOutput=true;c.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}}
            val status=c.responseCode
            val body=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(status==401||status==403) throw IOException("Notion rejected the token or integration access. Check token and share the database with the integration.")
            if(status !in 200..299) throw IOException("Notion API HTTP "+status+". "+body.take(160))
            return JSONObject(body)
        } finally {c.disconnect()}
    }
    fun testConnection(token:String):String {
        val result=request("POST","search",token,JSONObject().put("page_size",1))
        return "Notion connection works. Test returned "+(result.optJSONArray("results")?.length()?:0)+" result(s)."
    }
    private fun titleKey(db:JSONObject):String {
        val props=db.optJSONObject("properties")?:JSONObject();val keys=props.keys()
        while(keys.hasNext()){val k=keys.next();if(props.optJSONObject(k)?.optString("type")=="title")return k}
        return "Name"
    }
    fun listTasks(token:String,databaseId:String):String {
        if(databaseId.isBlank())throw IOException("Enter your Notion task database ID in VANTA Settings first.")
        val db=request("GET","databases/"+databaseId.trim(),token);val key=titleKey(db)
        val rows=request("POST","databases/"+databaseId.trim()+"/query",token,JSONObject().put("page_size",10)).optJSONArray("results")?:JSONArray()
        if(rows.length()==0)return "Your connected Notion task database has no rows."
        val lines=mutableListOf<String>()
        for(i in 0 until rows.length()){
            val props=rows.optJSONObject(i)?.optJSONObject("properties")?:continue
            val title=props.optJSONObject(key)?.optJSONArray("title")?:continue
            val value=(0 until title.length()).mapNotNull{title.optJSONObject(it)?.optJSONObject("text")?.optString("content")}.joinToString("")
            if(value.isNotBlank())lines += "• "+value
        }
        return if(lines.isEmpty())"I found rows in Notion but could not read their title fields." else "Your Notion tasks:\n"+lines.joinToString("\n")
    }
    fun createTask(token:String,databaseId:String,task:String):String {
        if(databaseId.isBlank())throw IOException("Enter your Notion task database ID in VANTA Settings first.")
        val clean=task.trim().take(180);if(clean.isBlank())throw IOException("Tell me the task name.")
        val db=request("GET","databases/"+databaseId.trim(),token)
        val title=JSONObject().put("title",JSONArray().put(JSONObject().put("text",JSONObject().put("content",clean))))
        val body=JSONObject().put("parent",JSONObject().put("database_id",databaseId.trim())).put("properties",JSONObject().put(titleKey(db),title))
        request("POST","pages",token,body)
        return "Added “"+clean+"” to your Notion task database."
    }
}