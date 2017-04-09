//http://stackoverflow.com/questions/23261301/required-attribute-not-work-in-safari-browser
//Make all browser without html5 respect the 'required' field of form to prevent empty submit.
$("form").submit(function(e) {

    var ref = $(this).find("[required]");

    $(ref).each(function(){
        if ( $(this).val() == '' )
        {
            alert("Required field should not be blank.");

            $(this).focus();

            e.preventDefault();
            return false;
        }
    });  return true;
});

